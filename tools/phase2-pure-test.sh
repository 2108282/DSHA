#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HC="$ROOT/app/src/main/java/com/deepseekharness/app/HarnessController.java"
BI="$ROOT/app/src/main/java/com/deepseekharness/app/BackupInspector.java"
PS="$ROOT/app/src/main/java/com/deepseekharness/app/PtySession.java"
PF="$ROOT/app/src/main/java/com/deepseekharness/app/PtyTerminalFragment.java"
PC="$ROOT/app/src/main/java/com/deepseekharness/app/PluginController.java"
PL="$ROOT/app/src/main/java/com/deepseekharness/app/PluginFragment.java"
AD="$ROOT/app/src/main/java/com/deepseekharness/app/AboutDialog.java"
BS="$ROOT/app/src/main/java/com/deepseekharness/app/BackupScope.java"
BM="$ROOT/app/src/main/java/com/deepseekharness/app/BackupManager.java"
WS="$ROOT/app/src/main/java/com/deepseekharness/app/WorkspaceFragment.java"
WSL="$ROOT/app/src/main/res/layout/fragment_workspace.xml"
PLAYOUT="$ROOT/app/src/main/res/layout/fragment_plugins.xml"
PACK="$ROOT/tools/pack-local.sh"
VERSION_FILE="$ROOT/VERSION"
OFFLINE_FILE="$ROOT/OFFLINE_VERSION"
OFFLINE_ASSET="$ROOT/app/src/main/assets/offline-rootfs.version"
CONST="$ROOT/app/src/main/java/com/deepseekharness/app/Constants.java"
PROVISION="$ROOT/scripts/offline-provision.sh"
PREPARE="$ROOT/scripts/prepare-dsh-alpha-runtime.sh"
CI_BUNDLE="$ROOT/scripts/ci-make-offline-bundle.sh"
PROOT="$ROOT/app/src/main/java/com/deepseekharness/app/ProotBootstrap.java"
RUNTIME="$ROOT/app/src/main/java/com/deepseekharness/app/ContainerRuntime.java"
PY_BUNDLE="$ROOT/app/src/main/assets/runtime-python/python-runtime.tgz"

check() { grep -Fq "$2" "$1" || { echo "FAIL: $3"; exit 1; }; echo "ok: $3"; }
check "$HC" "AtomicBoolean hmrRetryUsed" "HMR retry has a one-shot guard"
check "$HC" "low.contains(\"--expose-internals is required\")" "HMR requires the known marker"
check "$HC" "low.contains(\"cordis-plugin-hmr\")" "HMR requires the known plugin"
check "$HC" "healHmrAndRetry(out)" "known HMR failure invokes recovery"
check "$PS" "WeakReference<Listener>" "PTY listener is weak"
check "$PS" "void attachListener(Listener l)" "PTY listener can attach"
check "$PS" "void detachListener(Listener l)" "PTY listener can detach"
check "$PF" "s.detachListener(this)" "fragment detaches listener on view destroy"
check "$PF" "target.postOnAnimation" "PTY redraw is frame-coalesced"
check "$PF" "onEmulatorSet()" "emulator callback remains covered"
check "$PC" "isHmrPlugin(pkg) || isHmrPlugin(fallbackSpec)" "controller rejects HMR sources"
check "$PC" "已还原安装前 profile" "failed install reports automatic rollback"
check "$PL" "不兼容当前移动端运行方式" "market labels HMR incompatibility"
check "$BS" "static final int SETTINGS = 3" "settings backup scope exists"
check "$BS" "DSHA-settings-" "settings backup has an isolated prefix"
check "$BS" "settings.yaml" "settings scope maps to upstream settings"
check "$BM" "LATEST_BACKUP_NAME = \"DSHA-backup-latest.tar.gz\"" "new backups use one latest name"
check "$BM" "deleteSameNameExcept" "latest publish removes old duplicate only after write"
check "$BM" "ATOMIC_MOVE" "direct latest backup uses atomic replacement"
if grep -Fq "AUTO_BACKUP_NAME" "$BM"; then
  echo "FAIL: dual-slot auto backup constants remain" >&2
  exit 1
fi
check "$WS" "setSingleChoiceItems" "workspace exposes mutually exclusive backup ranges"
check "$WS" "即将备份：" "workspace confirms the selected range"
check "$BM" "return backup(ctx, c, LATEST_BACKUP_NAME, scope);" "selected range reaches the packer"
check "$BI" "String createdAt = \"\"" "backup inspector reads creation time"
check "$HC" "if (!info.looksLikeDsha)" "unrecognized archive is rejected before restore staging"
check "$HC" "不是可识别的 DSHA 备份" "unrecognized archive reports a clear restore error"
check "$HC" "备份类型：" "restore result shows backup type"
check "$HC" "创建时间：" "restore result shows creation time"
check "$HC" "包含内容：" "restore result shows included content"
check "$WSL" "选择备份范围" "workspace opens the range chooser"
check "$WSL" "可选全部数据、对话记录、设置或插件" "workspace explains all four ranges"
check "$WSL" "默认不包含 API Key" "workspace explains the default key policy"
check "$PLAYOUT" "android:minWidth=\"220dp\"" "GitHub input has a mobile minimum width"
check "$PLAYOUT" "android:scrollHorizontally=\"true\"" "GitHub input scrolls long links"
check "$AD" 'QQ_GROUP = "975836806"' "QQ group display uses the requested number"
check "$AD" '"&uin=" + QQ_GROUP' "QQ group button jumps to the same number"
check "$PACK" "dsha-1.2.0-alpha.2-vc104.apk" "packaging publishes the fixed alpha2 release artifact"
check "$CONST" "dsh-v0.1.2-alpha.2" "Android runtime id is pinned to alpha2"
check "$CONST" "0a53fb55bea101816fa226bb964ae2bed71c343b" "Android runtime commit is pinned"
check "$PREPARE" "DSH_TAG=\"dsh-v0.1.2-alpha.2\"" "native closure script pins alpha2 tag"
check "$PREPARE" "DSH_COMMIT=\"0a53fb55bea101816fa226bb964ae2bed71c343b\"" "native closure script pins alpha2 commit"
check "$PROVISION" "EXPECTED_DSH_VERSION=\"0.1.2-alpha.2\"" "rootfs provisioner pins alpha2 version"
check "$CI_BUNDLE" "upstreamCommit: '0a53fb55bea101816fa226bb964ae2bed71c343b'" "rootfs metadata verifier pins alpha2 commit"
check "$VERSION_FILE" "1.2.0-alpha.2" "VERSION mirrors alpha2"
check "$OFFLINE_FILE" "5" "offline version is incremented for changed runtime"
check "$OFFLINE_ASSET" "5" "offline asset marker mirrors offline version"
check "$PROOT" 'ctx.getAssets().open("runtime-python/python-runtime.tgz")' "restore runtime uses the packaged Python archive"
check "$PROOT" 'data/data/com.termux/files/usr/lib/python3.14/os.py' "bundled Python validates its standard library"
check "$PROOT" 'data/data/com.termux/files/usr/lib/libandroid-support.so' "bundled Python validates Android support library"
check "$RUNTIME" '{"/system"}' "bundled Python exposes Android system linker"
check "$RUNTIME" '{"/apex"}' "bundled Python exposes Android APEX libraries"
check "$RUNTIME" '{"/linkerconfig"}' "bundled Python exposes Android linker configuration"
if [ ! -s "$PY_BUNDLE" ]; then
  echo "FAIL: packaged Python archive is missing or empty" >&2
  exit 1
fi
PY_ENTRIES="$(tar -tzf "$PY_BUNDLE")"
for entry in './bin/python3.14' \
             './data/data/com.termux/files/usr/lib/python3.14/os.py' \
             './data/data/com.termux/files/usr/lib/libpython3.14.so' \
             './data/data/com.termux/files/usr/lib/libandroid-support.so'; do
  grep -Fxq "$entry" <<<"$PY_ENTRIES" \
    || { echo "FAIL: packaged Python archive lacks $entry" >&2; exit 1; }
done
echo "ok: packaged Python archive contains interpreter and runtime closure"
check "$PACK" "cleanup_pack_stages" "successful packaging removes controlled temporary stage directories"
check "$PACK" "pack-local-*' -o -name 'full-build'" "temporary cleanup is restricted to pack-local/full-build directories"
if grep -Fq "tagged=" "$PACK"; then
  echo "FAIL: pack script still emits hash aliases" >&2
  exit 1
fi
echo "phase2 pure checks passed"
