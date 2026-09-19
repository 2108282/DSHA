#!/system/bin/sh
ROOTFS="/data/adb/dsha/rootfs"
RUN_DIR="/data/adb/dsha/run"
PID_FILE="$RUN_DIR/dsh.pid"
PORT_FILE="$RUN_DIR/port"
LOG_FILE="$RUN_DIR/dsh-web.log"

PORT="${1:-3088}"
TASKSET_CPUS="${2:-}"
case "$PORT" in
    ''|*[!0-9]*) PORT=3088 ;;
esac

mkdir -p "$RUN_DIR"
chmod 777 "$RUN_DIR" 2>/dev/null || true
echo "$PORT" > "$PORT_FILE" 2>/dev/null || true

# 1. 检查是否已经在运行
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "STATUS:ALREADY_RUNNING PID:$OLD_PID PORT:$PORT"
        TOKEN_FILE="$ROOTFS/root/.dsh/.bridge_token"
        [ -s "$TOKEN_FILE" ] && echo "BRIDGE_TOKEN:$(cat "$TOKEN_FILE" 2>/dev/null)"
        if [ -f "$RUN_DIR/lan_enabled" ]; then
            [ -f "/data/adb/dsha/scripts/lan-proxy.sh" ] && sh "/data/adb/dsha/scripts/lan-proxy.sh" start >/dev/null 2>&1 || true
            [ -f "$RUN_DIR/lan-proxy.pid" ] && kill -0 "$(cat "$RUN_DIR/lan-proxy.pid" 2>/dev/null)" 2>/dev/null && echo "LAN_STATUS:RUNNING PORT:3081"
        fi
        grep -o "http://127\.0\.0\.1:[0-9]*/?token=[^ ]*" "$LOG_FILE" 2>/dev/null | tail -n 1
        exit 0
    fi
    rm -f "$PID_FILE"
fi

# 2. 解除 Android 12+ 幽灵进程限制
/system/bin/device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null

# 2.3 深度破除残留死锁：清理上次异常退出遗留的 stale .lock 文件，防止 atomic-write 写入超时卡死
rm -f "$ROOTFS/root/.dsh/"*.lock 2>/dev/null || true
rm -f "$ROOTFS/root/.dsh/."*.lock 2>/dev/null || true
rm -f "$ROOTFS/root/.dsh/.credentials.yaml.lock" 2>/dev/null || true

# 2.5 自动补齐 CA 根证书与前端首帧防闪白样式
mkdir -p "$ROOTFS/etc/ssl/certs" "$ROOTFS/usr/lib/ssl" 2>/dev/null || true
if [ -f "$ROOTFS/usr/local/share/dsha/ca-certificates.crt" ]; then
    if [ ! -f "$ROOTFS/etc/ssl/certs/ca-certificates.crt" ]; then
        cp -f "$ROOTFS/usr/local/share/dsha/ca-certificates.crt" "$ROOTFS/etc/ssl/certs/ca-certificates.crt" 2>/dev/null || true
        chmod 644 "$ROOTFS/etc/ssl/certs/ca-certificates.crt" 2>/dev/null || true
    fi
    if [ ! -f "$ROOTFS/usr/lib/ssl/cert.pem" ]; then
        cp -f "$ROOTFS/usr/local/share/dsha/ca-certificates.crt" "$ROOTFS/usr/lib/ssl/cert.pem" 2>/dev/null || true
        chmod 644 "$ROOTFS/usr/lib/ssl/cert.pem" 2>/dev/null || true
    fi
fi
INDEX_HTML="$ROOTFS/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html"
if [ -f "$INDEX_HTML" ] && ! grep -q "dsh-boot-style" "$INDEX_HTML"; then
    sed -i 's|<head>|<head><style id="dsh-boot-style">html,body,#root{background:transparent!important;background-color:transparent!important;}</style>|' "$INDEX_HTML" 2>/dev/null || true
fi

# 3. 挂载原生虚拟文件系统（基于 /proc/mounts 精准判重，杜绝挂载泄漏与层叠）
is_mounted() {
    local target="${1%/}"
    grep -q " $target " /proc/mounts 2>/dev/null || mountpoint -q "$target" 2>/dev/null
}

mount_if_needed() {
    local target="$1"
    shift
    if ! is_mounted "$target"; then
        mkdir -p "$target" 2>/dev/null
        mount "$@" "$target"
        # 严禁对 FUSE 文件系统（/sdcard、/storage）或虚拟设备（/dev）追加 remount，否则会导致 Android vold abort FUSE 连接引发热重启
        case "$target" in
            */sdcard*|*/storage*|*/dev*)
                ;;
            *)
                case "$*" in
                    *bind*)
                        mount -o remount,bind,noatime,nodiratime "$target" 2>/dev/null || true
                        ;;
                esac
                ;;
        esac
    fi
}

# 优化 rootfs 宿主挂载参数，消除访问时间写回损耗
mount -o remount,noatime,nodiratime "$ROOTFS" 2>/dev/null || true

mount_if_needed "$ROOTFS/dev" -o bind /dev
mount_if_needed "$ROOTFS/dev/pts" -o bind /dev/pts
mkdir -p "$ROOTFS/dev/shm"
mount_if_needed "$ROOTFS/dev/shm" -t tmpfs tmpfs -o mode=1777,noatime,nodiratime
# 屏蔽物理块设备：只读且mode 000空tmpfs，从内核层彻底杜绝误写分区物理变砖
mkdir -p "$ROOTFS/dev/block"
mount_if_needed "$ROOTFS/dev/block" -t tmpfs tmpfs -o ro,mode=000
mount_if_needed "$ROOTFS/proc" -t proc proc
mount_if_needed "$ROOTFS/sys" -t sysfs sysfs

# 挂载存储卡 (启用 noatime,nodiratime)
if [ -d "/storage/emulated/0" ]; then
    mount_if_needed "$ROOTFS/sdcard" -o bind /storage/emulated/0
    mount_if_needed "$ROOTFS/storage/emulated/0" -o bind /storage/emulated/0
elif [ -d "/sdcard" ]; then
    mount_if_needed "$ROOTFS/sdcard" -o bind /sdcard
fi

# 确保手机 Download/DSHA/工作区 存在，并在容器 root 下建立「内部存储」软链接直通
mkdir -p "$ROOTFS/sdcard/Download/DSHA/工作区" 2>/dev/null || true
mkdir -p "$ROOTFS/root" 2>/dev/null || true
rm -f "$ROOTFS/root/内部存储" 2>/dev/null || true
ln -sf /sdcard/Download/DSHA "$ROOTFS/root/内部存储" 2>/dev/null || true

# 4. 修复 DNS 配置（若缺失则写入稳定公共 DNS）
mkdir -p "$ROOTFS/etc"
if [ ! -f "$ROOTFS/etc/resolv.conf" ] || [ ! -s "$ROOTFS/etc/resolv.conf" ]; then
    rm -f "$ROOTFS/etc/resolv.conf" 2>/dev/null || true
    cat << 'DNS_EOF' > "$ROOTFS/etc/resolv.conf"
nameserver 223.5.5.5
nameserver 119.29.29.29
nameserver 1.1.1.1
DNS_EOF
fi

# 5. 部署守护包装器与安全策略（按需生成，免除每次重复 I/O）
DSH_BIN="$ROOTFS/root/dsh-bin"
mkdir -p "$DSH_BIN"
mkdir -p "$ROOTFS/root/.dsh"
chmod 777 "$ROOTFS/root/.dsh" 2>/dev/null || true

TOKEN_FILE="$ROOTFS/root/.dsh/.bridge_token"
if [ ! -s "$TOKEN_FILE" ]; then
    tr -dc 'a-zA-Z0-9' < /dev/urandom 2>/dev/null | head -c 32 > "$TOKEN_FILE" 2>/dev/null || echo "dsha_$(date +%s%N)" > "$TOKEN_FILE"
fi
chmod 666 "$TOKEN_FILE" 2>/dev/null || true
CURRENT_TOKEN=$(cat "$TOKEN_FILE" 2>/dev/null)

# 写入确认交互脚本（支持 10 分钟临时免打扰租约、临时文件白名单放行与双端口兼容）
cat << 'CONFIRM_EOF' > "$ROOTFS/root/dsh-confirm.sh"
#!/bin/bash
# 用法：dsh-confirm.sh [--force] <命令...>

# 1. 检查统一的 10 分钟临时免审租约
if [ -f /root/.dsh/.auth_lease ]; then
  EXP=$(cat /root/.dsh/.auth_lease 2>/dev/null)
  NOW=$(date +%s)
  if [ -n "$EXP" ] && [ "${NOW:-0}" -lt "${EXP%.*}" ]; then
    exit 0
  fi
fi

# 2. 安全临时文件清理白名单（删除截图、临时文件免弹窗）
is_safe_cleanup() {
  local c="$1"
  [[ "$c" =~ ^(rm|unlink)[[:space:]] ]] || return 1
  [[ "$c" =~ -r|-R|\* ]] && return 1
  for arg in $c; do
    [[ "$arg" =~ ^(rm|unlink|-f|-v)$ ]] && continue
    if [[ "$arg" =~ ^/sdcard/Download/DSHA/.*(png|jpg|jpeg|tmp)$ ]] || \
       [[ "$arg" =~ ^/sdcard/Download/.*(png|jpg|jpeg|tmp)$ ]] || \
       [[ "$arg" =~ ^/tmp/.* ]] || \
       [[ "$arg" == "/root/.dsh/.auth_lease" ]]; then
      continue
    else
      return 1
    fi
  done
  return 0
}

FORCE=0
if [ "$1" = "--force" ]; then FORCE=1; shift; fi
CMD="$*"

if [ "$FORCE" != "1" ] && is_safe_cleanup "$CMD"; then
  exit 0
fi

TOKEN=$(cat /root/.dsh/.bridge_token 2>/dev/null)
RES=""
for PORT in 3095 3090; do
  RES=$(curl -s -m 65 -G "http://127.0.0.1:$PORT/confirm" --data-urlencode "cmd=$CMD" --data-urlencode "force=$FORCE" -H "X-Token: $TOKEN" 2>/dev/null)
  case "$RES" in
    *'"result":"YES"'*|*'"result":YES'*) exit 0 ;;
    *'"result":"NO"'*|*'"result":NO'*)  echo "已拒绝: $CMD（用户在手机端拒绝了该操作）" >&2; exit 1 ;;
  esac
  [ -n "$RES" ] && break
done

if [ -n "$DSH_INTERACTIVE" ]; then
  echo -n "确认执行危险操作 [$CMD] ? [y/N] " >&2
  read -t 10 ans
  case "$ans" in y|Y) exit 0 ;; esac
fi

echo "已拦截高危操作: $CMD (3090确认服务未就绪或超时)" >&2
exit 1
CONFIRM_EOF
chmod 755 "$ROOTFS/root/dsh-confirm.sh" 

# 写入函数级命令守卫
if [ ! -f "$ROOTFS/root/dsh-guard.sh" ]; then
cat << 'GUARD_EOF' > "$ROOTFS/root/dsh-guard.sh"
# DSHA 危险命令守卫
if [ "${DSH_CONFIRM:-0}" = "1" ] || [ "${DSH_SHELL:-0}" = "1" ]; then
  rm()       { /root/dsh-confirm.sh "rm $*" && /usr/bin/rm "$@"; }
  rmdir()    { /root/dsh-confirm.sh "rmdir $*" && /usr/bin/rmdir "$@"; }
  unlink()   { /root/dsh-confirm.sh "unlink $*" && /usr/bin/unlink "$@"; }
  truncate() { /root/dsh-confirm.sh "truncate $*" && /usr/bin/truncate "$@"; }
  dd()       { /root/dsh-confirm.sh "dd $*" && /usr/bin/dd "$@"; }
  mkfs()     { /root/dsh-confirm.sh "mkfs $*" && /usr/sbin/mkfs "$@"; }
  mkfs.ext4(){ /root/dsh-confirm.sh "mkfs.ext4 $*" && /usr/sbin/mkfs.ext4 "$@"; }
  mkfs.vfat(){ /root/dsh-confirm.sh "mkfs.vfat $*" && /usr/sbin/mkfs.vfat "$@"; }
  fdisk()    { /root/dsh-confirm.sh "fdisk $*" && /usr/sbin/fdisk "$@"; }
  reboot()   { /root/dsh-confirm.sh "reboot $*" && /usr/sbin/reboot "$@"; }
  shutdown() { /root/dsh-confirm.sh "shutdown $*" && /usr/sbin/shutdown "$@"; }
  halt()     { /root/dsh-confirm.sh "halt $*" && /usr/sbin/halt "$@"; }
  poweroff() { /root/dsh-confirm.sh "poweroff $*" && /usr/sbin/poweroff "$@"; }
  wipe()     { /root/dsh-confirm.sh "wipe $*" && /usr/sbin/wipe "$@"; }
fi
GUARD_EOF
chmod 755 "$ROOTFS/root/dsh-guard.sh"
fi

# 注入 bashrc 自动加载
if ! grep -q "dsh-guard.sh" "$ROOTFS/root/.bashrc" 2>/dev/null; then
    echo '[ -f /root/dsh-guard.sh ] && source /root/dsh-guard.sh' >> "$ROOTFS/root/.bashrc"
fi

# PATH 级命令守卫包装（若缺失则补齐）
if [ ! -f "$DSH_BIN/rm" ]; then
for C in rm rmdir unlink truncate dd mkfs mkfs.ext4 mkfs.vfat fdisk reboot shutdown halt poweroff wipe; do
cat << 'WRAPPER_EOF' > "$DSH_BIN/$C"
#!/bin/bash
SELF=$(basename "$0")
REAL=""
for p in /usr/local/bin /usr/bin /bin /usr/sbin /sbin; do
  if [ -x "$p/$SELF" ] && [ "$p/$SELF" != "$0" ]; then REAL="$p/$SELF"; break; fi
done
[ -z "$REAL" ] && REAL=$(ls /usr/local/bin/$SELF /usr/bin/$SELF /bin/$SELF 2>/dev/null | head -1)
if [ -z "$REAL" ]; then echo "找不到真实命令: $SELF" >&2; exit 127; fi
if [ "${DSH_CONFIRM:-0}" != "1" ] && [ "${DSH_SHELL:-0}" != "1" ]; then
  exec "$REAL" "$@"
fi
if /root/dsh-confirm.sh "$SELF $*"; then
  exec "$REAL" "$@"
fi
echo "已拒绝: $SELF $*" >&2
exit 1
WRAPPER_EOF
chmod 755 "$DSH_BIN/$C"
done
fi

# 宿主 Android 命令穿透直通包装（利用 nsenter 映射宿主 /system/bin 命令）
for HCMD in am pm cmd input screencap dumpsys getprop logcat; do
cat << HCMD_EOF > "$DSH_BIN/$HCMD"
#!/bin/bash
exec /usr/bin/nsenter -t 1 -m /system/bin/$HCMD "\$@"
HCMD_EOF
chmod 755 "$DSH_BIN/$HCMD"
done

# 清空旧日志
> "$LOG_FILE"
mkdir -p "$ROOTFS/root"
ln -sf "$LOG_FILE" "$ROOTFS/root/dsh-web.log" 2>/dev/null || true

# 确保本机极速低功耗心跳与轮询补丁就绪（杜绝退化为 2s 默认心跳）
if [ ! -f "$ROOTFS/root/.dsh/heartbeat-patch.yml" ]; then
    mkdir -p "$ROOTFS/root/.dsh" 2>/dev/null || true
    cat << 'HB_EOF' > "$ROOTFS/root/.dsh/heartbeat-patch.yml"
- id: typert-gateway
  config:
    websocketHeartbeatIntervalMs: 2147483647
- id: skill-filesystem
  config:
    watchPollIntervalMs: 60000
HB_EOF
    chmod 600 "$ROOTFS/root/.dsh/heartbeat-patch.yml" 2>/dev/null || true
fi

PATCH_ARG=""
if [ -f "$ROOTFS/root/.dsh/heartbeat-patch.yml" ]; then
    PATCH_ARG="--patch /root/.dsh/heartbeat-patch.yml"
fi

# 内存防碎片治理：自适应检测 jemalloc，存在则定向注入 Node 主进程压制长时间挂机内存碎片
PRELOAD_OPT=""
if [ -f "$ROOTFS/usr/lib/aarch64-linux-gnu/libjemalloc.so.2" ]; then
    PRELOAD_OPT="LD_PRELOAD=/usr/lib/aarch64-linux-gnu/libjemalloc.so.2"
fi

# 6. 原生拉起 Node.js DSH Web 服务
chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/root \
    USER=root \
    LOGNAME=root \
    PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    SSL_CERT_FILE=/usr/lib/ssl/cert.pem \
    TERM=xterm-256color \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    DSH_CONFIRM=1 \
    NODE_PATH=/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules \
    $PRELOAD_OPT \
    nice -n 10 /usr/local/bin/node --v8-pool-size=2 /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web $PATCH_ARG --no-open --port "$PORT" --host 127.0.0.1 > "$LOG_FILE" 2>&1 &

NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"
echo -800 > "/proc/$NEW_PID/oom_score_adj" 2>/dev/null || true

# 纳入系统后台 cpuctl 组（仅限制频率上限与能耗调度，绝对不覆盖/干预核心亲和性）
if [ -d "/dev/cpuctl/background" ]; then
    echo "$NEW_PID" > /dev/cpuctl/background/cgroup.procs 2>/dev/null || true
fi

# 确保进程进入根 cpuset，解除受限核心屏蔽，使后续 taskset 可以自由绑定任意 0-7 核心
if [ -f "/dev/cpuset/cgroup.procs" ]; then
    echo "$NEW_PID" > /dev/cpuset/cgroup.procs 2>/dev/null || true
fi

# 核心亲和性：100% 严格遵循用户在 APK 设置中配置的 Taskset（留空则不干预，由系统全核自由调度）
if [ -z "$TASKSET_CPUS" ]; then
    if [ -s "$RUN_DIR/taskset" ]; then
        TASKSET_CPUS=$(cat "$RUN_DIR/taskset" 2>/dev/null | tr -d ' \n\r')
    elif [ -s "$ROOTFS/root/.dsh/taskset" ]; then
        TASKSET_CPUS=$(cat "$ROOTFS/root/.dsh/taskset" 2>/dev/null | tr -d ' \n\r')
    fi
fi
if [ -n "$TASKSET_CPUS" ]; then
    chroot "$ROOTFS" /usr/bin/taskset -a -p -c "$TASKSET_CPUS" "$NEW_PID" >/dev/null 2>&1 || true
fi

# 7. 等待服务启动并提取鉴权 Token 链接（1s 轮询 60 次，就绪即刻返回）
AUTH_URL=""
for i in $(seq 1 60); do
    AUTH_URL=$(grep -o "http://127\.0\.0\.1:${PORT}/?token=[^ ]*" "$LOG_FILE" 2>/dev/null | tail -n 1)
    [ -z "$AUTH_URL" ] && AUTH_URL=$(grep -o 'http://127\.0\.0\.1:[0-9]*/?token=[^ ]*' "$LOG_FILE" 2>/dev/null | tail -n 1)
    if [ -n "$AUTH_URL" ]; then
        break
    fi
    if ! kill -0 "$NEW_PID" 2>/dev/null; then
        echo "STATUS:FAILED"
        cat "$LOG_FILE"
        exit 1
    fi
    sleep 1
done

echo "STATUS:STARTED PID:$NEW_PID PORT:$PORT"
[ -n "$CURRENT_TOKEN" ] && echo "BRIDGE_TOKEN:$CURRENT_TOKEN"
if [ -f "$RUN_DIR/lan_enabled" ]; then
    if [ -f "/data/adb/dsha/scripts/lan-proxy.sh" ]; then
        sh "/data/adb/dsha/scripts/lan-proxy.sh" start >/dev/null 2>&1 || true
    fi
    [ -f "$RUN_DIR/lan-proxy.pid" ] && kill -0 "$(cat "$RUN_DIR/lan-proxy.pid" 2>/dev/null)" 2>/dev/null && echo "LAN_STATUS:RUNNING PORT:3081"
fi

# 动态同步 KernelSU / Magisk 模块描述状态
for p_mod in "/data/adb/modules/dsha_native/module.prop" \
             "/data/adb/modules_update/dsha_native/module.prop"; do
    if [ -f "$p_mod" ]; then
        sed -i "s|^description=.*|description=[🟢 运行中 :${PORT}] DSHA 原生 Linux chroot 极速运行时，按需启停。|" "$p_mod" 2>/dev/null || true
    fi
done

if [ -n "$AUTH_URL" ]; then
    echo "=========================================================="
    echo "进入 Web 鉴权链接 (直接在手机浏览器打开):"
    echo "$AUTH_URL"
    echo "=========================================================="
else
    echo "提示: 尚未在日志中捕获到 Token，后台仍正常启动中，日志见 $LOG_FILE"
fi