# DSHA 极简 Ubuntu ARM64 运行底座制作与深度裁剪指南 (HOW_TO_BUILD_MINIMAL_ROOTFS.md)

本文档面向 DSHA 开发者，完整拆解当前系统底座（~190MB 原生运行环境 / ~60MB 压缩底包）的**深度裁剪原理**，并提供**从 Canonical 官方源从零构建、配置、适配 Android Chroot 并最终打包的完整 SOP 流程**。

---

## 一、 当前底座是怎么做到极致精简的？（裁剪原理剖析）

普通 Ubuntu 24.04 Server 安装后体积高达 1.5GB ~ 2.5GB，而当前底座核心未安装 DSH 前仅约 **190MB**（压缩后约 **60MB**）。其精简核心在于**按 Android Chroot 运行时的真实需求实施了 5 大层级的物理剥离**：

### 1. 宿主内核与守护进程脱钩（剥离 Systemd / Udev）
* **原理**：Android 设备使用手机自身 Linux 内核，`init` 进程由 Android 系统的 `/system/bin/init`（PID=1）全权接管。
* **做法**：
  - 彻底剔除 `systemd`、`udev`、`dbus`、`cron`、`syslog`、`polkit` 等后台系统服务守护进程；
  - 剔除开机引导启动逻辑（GRUB、initramfs、内核镜像 modules）；
  - 容器启动只靠 Magisk/KernelSU 脚本执行 `chroot /data/adb/dsha/rootfs /bin/sh`，节省了 400MB+ 的系统无用开销。

### 2. Dpkg 路径级过滤策略（剥离静态多媒体与说明文档）
* **原理**：Debian/Ubuntu 软件包安装时，约 30%~50% 的体积由帮助手册（man）、开源说明（doc）、更新日志（changelog）和多语言翻译文件（locale）构成。
* **做法**：
  - 在 `/etc/dpkg/dpkg.cfg.d/01_nodoc` 中配置 `path-exclude` 规则：
    - 排除 `/usr/share/doc/*`
    - 排除 `/usr/share/man/*`
    - 排除 `/usr/share/info/*`
    - 排除 `/usr/share/locale/*`（仅保留 `C.UTF-8`）
  - apt 安装任何软件包时，上述无用文件根本不会写入磁盘。

### 3. 依赖按需精简（基于 `minbase` 变体）
* **原理**：普通发行版包含大量网络管理（NetworkManager、netplan）、防火墙（ufw、iptables）、硬件驱动等。在 Android 手机中，网络硬件和 WiFi/蜂窝通信全由 Android 框架直接调度。
* **做法**：
  - 仅采用 `minbase` 最小包集，只保留最基础的 `glibc`、`bash`、`coreutils`、`tar`、`curl`、`ca-certificates` 与基础工具；
  - 整个系统已安装的 deb 包总数严格控制在 **120 ~ 130 个**之间。

### 4. 包管理器与日志零残留
* **做法**：
  - 清空 `/var/lib/apt/lists/*`（APT 本地索引缓存）；
  - 清空 `/var/cache/apt/archives/*`（deb 安装包缓存）；
  - 清空 `/var/log/*` 并重置 `/etc/machine-id`。

### 5. 独立运行时二进制分发（Node.js / Python）
* **做法**：
  - **Node.js**：不走臃肿的 apt 安装，直接使用 Node.js 官方预编译的纯 64 位 ARM64 单一归档解压至 `/usr/local/`，剥离 node-gyp 冗余依赖；
  - **内存碎片治理**：注入轻量级 `libjemalloc.so.2`，压制长期运行产生的内存毛刺。

---

## 二、 从零制作全新底包的完整步骤（SOP）

无论是在 Linux x86_64 电脑（需安装 `qemu-user-static`）还是在一台已 Root 的 Android 手机上，制作全新底包的标准流程如下：

### 步骤 1：获取官方最小基准包 (ubuntu-base)

Canonical 官方针对容器和嵌入式系统提供了经过官方校验的 `ubuntu-base`：

```bash
# 创建工作目录
mkdir -p ~/dsha-rootfs-build && cd ~/dsha-rootfs-build

# 下载官方 Ubuntu 24.04 (Noble Numbat) ARM64 最小基准镜像
wget http://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz

# 解压到 rootfs 目录
mkdir -p rootfs
tar -xzf ubuntu-base-24.04-base-arm64.tar.gz -C rootfs
```

---

### 步骤 2：配置 DNS 与 Dpkg 瘦身策略

在进入环境前，先注入瘦身规则与 DNS：

```bash
# 1. 注入权威公共 DNS
cat << 'EOF' > rootfs/etc/resolv.conf
nameserver 223.5.5.5
nameserver 119.29.29.29
nameserver 1.1.1.1
EOF

# 2. 注入 Dpkg 路径过滤规则（永久杜绝帮助文档与语言包写入）
mkdir -p rootfs/etc/dpkg/dpkg.cfg.d
cat << 'EOF' > rootfs/etc/dpkg/dpkg.cfg.d/01_nodoc
path-exclude /usr/share/doc/*
path-include /usr/share/doc/*/copyright
path-exclude /usr/share/man/*
path-exclude /usr/share/groff/*
path-exclude /usr/share/info/*
path-exclude /usr/share/lintian/*
path-exclude /usr/share/linda/*
path-exclude /usr/share/locale/*
path-include /usr/share/locale/en*
path-include /usr/share/locale/zh*
EOF
```

---

### 步骤 3：挂载虚拟文件系统并进入 Chroot

```bash
# 挂载宿主环境
mount -t proc /proc rootfs/proc
mount -t sysfs /sys rootfs/sys
mount -o bind /dev rootfs/dev
mount -o bind /dev/pts rootfs/dev/pts

# 进入环境
chroot rootfs /bin/bash
```

---

### 步骤 4：在 Chroot 内部安装核心软件与 Android 兼容映射

进入 chroot 后的命令行提示符为 `root@...:/#`：

```bash
# 1. 配置标准环境变量
export HOME=/root
export LC_ALL=C.UTF-8
export LANG=C.UTF-8
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# 2. 更新软件源并安装基础工具链
apt-get update
apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    python3 \
    python3-minimal \
    xz-utils \
    tar \
    gzip \
    libjemalloc2 \
    procps

# 3. 关键：注入 Android 系统 GID 映射（解决存储卡读写与网络权限）
# Android 内核对网络和存储卡实施了严格的 GID 校验
cat << 'EOF' >> /etc/group
aid_system:x:1000:root
aid_radio:x:1001:root
aid_graphics:x:1003:root
aid_input:x:1004:root
aid_sdcard:x:1023:root
aid_media_rw:x:1023:root
aid_shell:x:2000:root
aid_inet:x:3003:root
aid_everybody:x:9997:root
EOF

# 4. 优化默认 Shell 与基础别名
echo 'export LANG=C.UTF-8' >> /root/.bashrc
echo 'export LC_ALL=C.UTF-8' >> /root/.bashrc
echo 'export PATH=/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' >> /root/.bashrc
```

---

### 步骤 5：部署官方最新 Node.js 与包管理器

```bash
# 1. 下载并安装官方预编译 Node.js 24 (纯 64 位 ARM64)
NODE_VER="v24.19.0"
curl -sSL "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-arm64.tar.xz" | tar -xJ -C /usr/local --strip-components=1

# 验证安装
node -v
npm -v

# 2. 安装 pnpm
npm install -g pnpm
```

---

### 步骤 6：预埋 Android 挂载桩与安全隔离目录

为了让 `start.sh` 能顺利挂载手机分区，必须在底包内建好空挂载目录：

```bash
mkdir -p /sdcard
mkdir -p /storage/emulated/0
mkdir -p /dev/block
mkdir -p /dev/shm
mkdir -p /dev/pts
mkdir -p /tmp
chmod 1777 /tmp
mkdir -p /root/.dsh
mkdir -p /root/dsh-bin
```

---

### 步骤 7：深度脱敏与收尾清理

在打包之前，必须将一切安装过程产生的垃圾清理干净：

```bash
# 1. 清理 APT 缓存
apt-get clean
rm -rf /var/lib/apt/lists/*
rm -rf /var/cache/apt/archives/*

# 2. 清理日志与临时文件
rm -rf /tmp/* /tmp/.* 2>/dev/null || true
rm -rf /var/log/* /var/tmp/*
> /etc/machine-id

# 3. 清理历史记录
rm -f /root/.bash_history

# 退出 chroot
exit
```

---

### 步骤 8：宿主解绑与打包发布

回到宿主机器后，解除挂载并打出标准的 `rootfs.tar.gz`：

```bash
# 1. 递归解除挂载
umount rootfs/dev/pts
umount rootfs/dev
umount rootfs/sys
umount rootfs/proc

# 2. 确认没有残留挂载
grep "$(pwd)/rootfs" /proc/mounts

# 3. 打包（保持文件权限与软链接）
cd rootfs
tar -czf ../rootfs.tar.gz .
cd ..

# 验证打包结果
ls -lh rootfs.tar.gz
```
*此时产出的 `rootfs.tar.gz` 就是一份纯净、极简、无任何个人隐私的通用 Android ARM64 原生底包（大小约 60MB ~ 75MB）。*

---

## 三、 结合 Magisk 模块完成最终封包

制作出 `rootfs.tar.gz` 后，与你的 Magisk 模块合体：

```bash
# 拷贝到模块打包目录
cp rootfs.tar.gz /sdcard/Download/DSHA/工作区/DSHA_apk/magisk-module/

# 打包全内置刷机包
cd /sdcard/Download/DSHA/工作区/DSHA_apk/magisk-module/
zip -r0 /sdcard/Download/DSHA/dsha_ksu_native_full.zip \
    META-INF module.prop customize.sh service.sh action.sh uninstall.sh scripts rootfs.tar.gz
```

---

## 四、 核心红线备忘

1. **绝对不要在挂载状态下执行 `tar -czf`**：否则手机内部存储会被递归打入压缩包；
2. **严禁在底包中预留 `.credentials.yaml` / `.bridge_token`**：必须由模块启动脚本在首启动时动态随机生成；
3. **保留 `/dev/block` 空目录**：`start.sh` 会在此处挂载 `mode=000` 的只读 tmpfs，确保手机硬件物理防砖。
