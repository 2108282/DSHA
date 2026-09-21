#!/usr/bin/env bash
# ============================================================
# DSHA 原生 Linux chroot (KernelSU / Magisk) 底包导出与打包工具
# 特性：
# 1. 纯只读脱敏：绝对不修改、不删除当前运行环境下的任何本地文件或配置；
# 2. 智能解耦：自动排除后装插件源码 (plugin-src/*) 及个人账号/密钥；
# 3. 完美修复依赖：动态注入仅含 4 大内置原生插件的标准 profiles/web/package.json，
#    彻底杜绝新机刷入后因后装插件断链导致 DSH 启动报错；
# 4. 双模式支持：--clean (默认，公开发布纯净底包) / --clone (个人整机换机备份)；
# 5. 支持 --full 参数直接联动 build-module.sh 一键生成最终刷机包。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_OUTPUT="/sdcard/Download/DSHA/rootfs.tar.gz"

MODE="clean"
OUTPUT_TAR="$DEFAULT_OUTPUT"
TRIGGER_FULL_BUILD=0

# 解析参数
while [ $# -gt 0 ]; do
    case "$1" in
        --clean)
            MODE="clean"
            shift
            ;;
        --clone)
            MODE="clone"
            shift
            ;;
        --full)
            TRIGGER_FULL_BUILD=1
            shift
            ;;
        -o|--output)
            OUTPUT_TAR="$2"
            shift 2
            ;;
        -h|--help)
            echo "用法: $0 [选项]"
            echo "选项:"
            echo "  --clean        [默认] 导出公开发布的纯净脱敏底包 (排除后装插件与私有数据，修复依赖)"
            echo "  --clone        导出完整克隆镜像 (保留所有后装插件、配置与会话，仅排除系统虚拟挂载)"
            echo "  --full         底包导出完成后，自动调用 scripts/build-module.sh 生成完整刷机包"
            echo "  -o, --output   指定底包输出路径 (默认: /sdcard/Download/DSHA/rootfs.tar.gz)"
            echo "  -h, --help     显示此帮助信息"
            exit 0
            ;;
        *)
            echo "未知参数: $1" >&2
            exit 1
            ;;
    esac
done

STAGE_DIR="/tmp/dsha_export_stage_$$"
mkdir -p "$STAGE_DIR"
cleanup() {
    python3 -c "import shutil; shutil.rmtree('$STAGE_DIR', ignore_errors=True)" 2>/dev/null || rm -rf "$STAGE_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "=========================================================="
echo "   DSHA 原生 Linux 底包导出引擎 (当前运行环境 -> rootfs.tar.gz)   "
echo "   运行模式: $MODE"
echo "=========================================================="

# 基础必须排除的系统虚拟文件系统与外部挂载
BASE_EXCLUDES=(
    "--exclude=./proc/*"
    "--exclude=./sys/*"
    "--exclude=./dev/*"
    "--exclude=./sdcard/*"
    "--exclude=./storage/*"
    "--exclude=./tmp/*"
    "--exclude=./run/*"
    "--exclude=./data/*"
    "--exclude=./mnt/*"
    "--exclude=./root/手机存储"
    "--exclude=./root/内部存储"
    "--exclude=./sdcard"
    "--exclude=./storage"
)

# 压缩引擎选择 (优先多线程 pigz)
COMPRESS_CMD="gzip -1"
if command -v pigz >/dev/null 2>&1; then
    COMPRESS_CMD="pigz -p 4 -1"
fi

if [ "$MODE" = "clone" ]; then
    echo "==> 正在执行【完整克隆备份】打包..."
    echo "    (保留当前所有个人插件、会话记录与系统配置)"
    
    mkdir -p "$(dirname "$OUTPUT_TAR")"
    cd /
    tar --numeric-owner -c "${BASE_EXCLUDES[@]}" . | $COMPRESS_CMD > "$OUTPUT_TAR"
    echo "✓ 完整克隆底包已生成: $OUTPUT_TAR ($(ls -lh "$OUTPUT_TAR" | awk '{print $5}'))"
else
    echo "==> 正在准备【公开发布纯净脱敏】打包..."
    echo "    [安全保障]: 当前本地系统文件零修改，所有脱敏重置仅在导出流中生效。"

    # 1. 在临时 Stage 中准备纯净的 profiles/web/package.json
    CLEAN_PROFILE_DIR="$STAGE_DIR/root/.dsh/profiles/web"
    mkdir -p "$CLEAN_PROFILE_DIR"

    cat > "$CLEAN_PROFILE_DIR/package.json" << 'EOF_PKG'
{
  "name": "dsh-profile-web",
  "private": true,
  "dependencies": {
    "dsh-device-shell-guide": "link:/root/dsha-device-shell-guide",
    "dsh-task-notifier": "link:/root/dsha-task-notifier",
    "dsh-status-overlay": "link:/root/dsha-status-overlay",
    "dsh-web-mobile": "link:/root/dsha-web-mobile"
  },
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app",
        "dsh-device-shell-guide",
        "dsh-task-notifier",
        "dsh-status-overlay",
        "dsh-web-mobile"
      ],
      "patchReload": "startup"
    }
  }
}
EOF_PKG

    # 2. 收集需要排除的死链 (profiles/web/node_modules 中所有指向 plugin-src/ 的软链)
    SYMLINK_EXCLUDES=()
    PROFILE_NM="/root/.dsh/profiles/web/node_modules"
    if [ -d "$PROFILE_NM" ]; then
        while IFS= read -r link_entry; do
            [ -L "$link_entry" ] || continue
            target=$(readlink "$link_entry" || true)
            if [[ "$target" == *"plugin-src"* ]]; then
                rel_path=".${link_entry}"
                SYMLINK_EXCLUDES+=("--exclude=$rel_path")
            fi
        done < <(find "$PROFILE_NM" -maxdepth 2 -type l 2>/dev/null || true)
    fi

    # 3. 全局 node_modules 中指向 plugin-src 的软链也一并排除
    GLOBAL_NM="/usr/local/lib/node_modules"
    if [ -d "$GLOBAL_NM" ]; then
        while IFS= read -r link_entry; do
            [ -L "$link_entry" ] || continue
            target=$(readlink "$link_entry" || true)
            if [[ "$target" == *"plugin-src"* ]]; then
                rel_path=".${link_entry}"
                SYMLINK_EXCLUDES+=("--exclude=$rel_path")
            fi
        done < <(find "$GLOBAL_NM" -maxdepth 2 -type l 2>/dev/null || true)
    fi

    # 4. 纯净模式脱敏黑名单
    CLEAN_EXCLUDES=(
        "${BASE_EXCLUDES[@]}"
        "${SYMLINK_EXCLUDES[@]}"
        # 排除当前含有后装插件的 package.json，后面追加注入纯净版
        "--exclude=./root/.dsh/profiles/web/package.json"
        # 排除个人会话、附件与存储
        "--exclude=./root/.dsh/sessions/*"
        "--exclude=./root/.dsh/attachments/*"
        "--exclude=./root/.dsh/storages/*"
        # 排除模型账号与 API Key
        "--exclude=./root/.dsh/agy-accounts.json"
        "--exclude=./root/.dsh/.credentials.yaml"
        "--exclude=./root/.dsh/dsh-api-dashboard.json"
        "--exclude=./root/.dsh/settings.yaml"
        # 排除设备桥与认证 Token
        "--exclude=./root/.dsh/.bridge_token"
        "--exclude=./root/.dsh/.launch_token"
        "--exclude=./root/.dsh/.lan_token"
        "--exclude=./root/.dsh/.approval_decision"
        "--exclude=./root/.dsh/.auth_lease"
        "--exclude=./root/.dsh/.anonymous-user-id"
        # 排除后装插件源码实体与历史
        "--exclude=./root/.dsh/plugin-src/*"
        "--exclude=./root/.dsh/plugin-previews/*"
        "--exclude=./root/.dsh/plugin-history/*"
        "--exclude=./root/.dsh/plugin-sources.json"
        "--exclude=./root/.dsh/plugin-updates.json"
        # 排除临时文件、缓存、日志与命令历史
        "--exclude=./root/.bash_history"
        "--exclude=./root/.cache/*"
        "--exclude=./root/*.log"
        "--exclude=./root/.*.pid"
        "--exclude=./root/.dsh.pre-restore*"
        "--exclude=./root/.dsha-backup*"
        "--exclude=./root/.dsh/__pycache__"
        "--exclude=./root/.dsh/repair-builtin.log"
        "--exclude=./root/.dsh/restore-report.txt"
        "--exclude=./root/dsha-repo"
        "--exclude=./.l2s"
        "--exclude=./.proroot-meta"
        "--exclude=./var/lib/apt/lists/*"
        "--exclude=./var/cache/apt/archives/*"
        "--exclude=./usr/share/doc/*"
        "--exclude=./usr/share/man/*"
    )

    mkdir -p "$(dirname "$OUTPUT_TAR")"
    TMP_RAW_TAR="$STAGE_DIR/raw_rootfs.tar"

    echo "==> [1/3] 正在扫描系统并创建脱敏底包流..."
    cd /
    tar --numeric-owner -cf "$TMP_RAW_TAR" "${CLEAN_EXCLUDES[@]}" .

    echo "==> [2/3] 动态注入纯净版 profiles/web/package.json (完美替换)..."
    tar --numeric-owner -rf "$TMP_RAW_TAR" -C "$STAGE_DIR" ./root/.dsh/profiles/web/package.json

    echo "==> [3/3] 正在执行多线程高效压缩..."
    $COMPRESS_CMD < "$TMP_RAW_TAR" > "$OUTPUT_TAR"
    rm -f "$TMP_RAW_TAR"

    echo "✓ 纯净脱敏底包已生成: $OUTPUT_TAR ($(ls -lh "$OUTPUT_TAR" | awk '{print $5}'))"
fi

echo "=========================================================="
echo "✓ 底包导出成功完成！"
echo "  底包路径: $OUTPUT_TAR"
echo "=========================================================="

# 如果指定了 --full，直接调用 build-module.sh 生成完整刷机包
if [ "$TRIGGER_FULL_BUILD" -eq 1 ]; then
    echo ""
    echo "==> 检测到 --full 标志，立即启动模块总装 (build-module.sh full)..."
    bash "$ROOT_DIR/scripts/build-module.sh" full
fi
