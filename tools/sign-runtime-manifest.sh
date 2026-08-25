#!/bin/bash
# 用 release 私钥给增量更新清单签名。
#
# 为什么必须签：增量更新是我们唯一主动开的远程代码通道。sha256 只保证「下载到的内容
# 与清单一致」，管不了「清单本身是不是我们发的」—— 仓库万一被攻破，攻击者改掉清单里
# 的 sha256，客户端照样会把恶意脚本当成合法更新装下去。签名把信任锚点从「GitHub 仓库
# 没被黑」挪到「私钥没泄露」，后者我们能控制。
#
# 客户端侧：APK 内置 assets/runtime-update-pubkey.pem，验签不过整批拒绝。
#
# 私钥处理：从 keystore 临时导出到 /tmp，用完立刻删（trap 兜底）。密码从
# ~/.gradle/gradle.properties 读（不入库），也可用环境变量覆盖。全程不 echo 密码。
set -eu

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$REPO_ROOT/runtime-manifest.json"
SIGFILE="$REPO_ROOT/runtime-manifest.sig"
PUBKEY="$REPO_ROOT/app/src/main/assets/runtime-update-pubkey.pem"
PROPS="$HOME/.gradle/gradle.properties"

prop() {
  grep "^$1=" "$PROPS" 2>/dev/null | head -1 | cut -d= -f2-
}

KS="${DSHA_KEYSTORE:-$(prop dsha.keystore)}"
PW="${DSHA_KEYSTORE_PASSWORD:-$(prop dsha.keystore.password)}"

if [ ! -f "$MANIFEST" ]; then
  echo "SIGN_SKIP: 没有 $MANIFEST，先跑 tools/gen-runtime-manifest.py"
  exit 0
fi
if [ -z "$KS" ] || [ ! -f "$KS" ]; then
  # 不是错误：没有私钥的人（外部贡献者）照样能构建，只是发不了签名清单
  echo "SIGN_SKIP: 找不到 keystore，跳过签名（清单仍在，但客户端会拒绝应用）"
  exit 0
fi

KEYPEM="$(mktemp /tmp/dsha-sign-XXXXXX.pem)"
RAWSIG=""
# 先留一份现有签名：自验不过时还原回去。**绝不能留下一个坏签名** ——
# 客户端 verifyManifest 验不过会整批拒绝更新，而 build.sh 对签名失败只是
# warning 不阻断，坏签名会一路跟着 commit 出去，谁都不会注意。
PREV_SIG=""
if [ -f "$SIGFILE" ]; then
  PREV_SIG="$(mktemp /tmp/dsha-prevsig-XXXXXX)"
  cp "$SIGFILE" "$PREV_SIG"
fi
cleanup() { rm -f "$KEYPEM" "$RAWSIG" "$PREV_SIG"; }
trap cleanup EXIT INT TERM

# PKCS12 → 无密码 PEM 私钥（只在临时文件里存在几秒）
if ! openssl pkcs12 -in "$KS" -nocerts -nodes -passin pass:"$PW" -out "$KEYPEM" 2>/dev/null; then
  echo "SIGN_FAIL: 私钥导出失败（keystore 密码不对？）"
  exit 1
fi

# 对清单原始字节做 SHA256withRSA 签名；产物存 base64 单行，便于入库与 HTTP 传输
if ! openssl dgst -sha256 -sign "$KEYPEM" "$MANIFEST" | base64 -w0 > "$SIGFILE"; then
  echo "SIGN_FAIL: 签名失败"
  exit 1
fi
printf '\n' >> "$SIGFILE"

# 签完立刻用公钥自验 —— 签了不验等于没签
if [ -f "$PUBKEY" ]; then
  RAWSIG="$(mktemp /tmp/dsha-sig-XXXXXX.bin)"
  base64 -d < "$SIGFILE" > "$RAWSIG"
  if openssl dgst -sha256 -verify "$PUBKEY" -signature "$RAWSIG" "$MANIFEST" >/dev/null 2>&1; then
    echo "SIGN_OK: $(wc -c < "$SIGFILE") 字节签名，已用 assets 内公钥自验通过"
  else
    echo "SIGN_FAIL: 自验不通过 —— 公钥与 keystore 不匹配，客户端会拒绝这批更新"
    if [ -n "$PREV_SIG" ]; then
      cp "$PREV_SIG" "$SIGFILE"
      echo "         已还原上一份签名（不把坏签名留在仓库里）"
    else
      rm -f "$SIGFILE"
      echo "         已删掉刚写出的坏签名"
    fi
    exit 1
  fi
else
  echo "SIGN_WARN: 缺 $PUBKEY，无法自验（客户端也就没法验签）"
fi
