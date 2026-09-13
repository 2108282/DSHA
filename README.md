# DSHA Native (KernelSU / Magisk) 原生 Linux 运行底座与模块

本项目为 **DeepSeek Harness (DSH)** 专为 Android 12+ Root 设备打造的纯原生 Linux (chroot) 模块与底座工程。

彻底弃用 PRoot / Proroot 等用户态 ptrace / LD_PRELOAD 虚拟化方案，将 Ubuntu ARM64 运行时直接部署于手机真实的 `ext4` 分区，实现 **0 虚拟化损耗、原生 glibc 性能、按需启停 0 待机功耗**。

> **分支定位说明**：
> * 本分支（`dsh-magisk`）仅负责 KernelSU / Magisk 模块、Linux 底座维护与控制脚本，**不包含任何 APK 代码**。
> * 配套前端控制外壳见分支：`magisk-apk`。

---

## 一、 系统架构与关键路径

```text
/data/adb/dsha/
├── rootfs/                         # 纯原生 Ubuntu ARM64 根文件系统
│   ├── bin/ -> usr/bin
│   ├── usr/local/bin/node          # Node.js 运行时 (v24.19.0)
│   ├── usr/local/bin/dsh           # DSH 核心命令行程序
│   └── root/.dsh/.bridge_token     # 3090 设备硬件能力鉴权 Token
│
├── scripts/                        # 核心生命周期控制脚本 (可执行权限 755)
│   ├── start.sh                    # 启动服务：安全挂载、配置网络、后台拉起 Node.js DSH
│   ├── stop.sh                     # 停止服务：多层卸载检测、杀残留进程、0 资源残留
│   ├── term.sh                     # 原生终端：直接以 Root 身份登录 chroot bash 交互终端
│   └── status.sh                   # 状态探针：检测 PID、端口与当前鉴权 Token
│
└── run/                            # 运行时状态目录
    ├── dsh.pid                     # 记录后台 DSH 主进程 PID
    └── dsh-web.log                 # 标准输出与错误重定向运行日志
```

---

## 二、 模块安装部署指南

### 方式 1：通过 KernelSU / APatch / Magisk 管理器刷入（推荐）
1. 将纯净底包 `rootfs.tar.gz` 放置在手机存储目录：
   ```text
   /sdcard/Download/DSHA/rootfs.tar.gz
   ```
   *（若刷机包内已打包内置 `rootfs.tar.gz`，则无需单独准备底包）*
2. 打开 KernelSU / APatch / Magisk 管理器，点击「模块」→「从本地安装」，选择 `dsha_ksu_native_v1.2.0.zip`。
3. 刷入脚本会自动完成控制脚本部署并解压底包至 `/data/adb/dsha/rootfs`。
4. **刷入完成后：无需重启手机！**（见下方免重启验证指南）。

### 方式 2：手动免刷入快速部署（开发者调试）
在 Root 终端（如 Termux `tsu` 或电脑 `adb shell su`）执行：
```bash
# 1. 创建目标目录
mkdir -p /data/adb/dsha/rootfs /data/adb/dsha/scripts /data/adb/dsha/run

# 2. 解压底包到真实分区
tar -xzf /sdcard/Download/DSHA/rootfs.tar.gz -C /data/adb/dsha/rootfs

# 3. 复制本仓库 magisk-module/scripts/ 下全部脚本到 /data/adb/dsha/scripts/
cp -rf magisk-module/scripts/* /data/adb/dsha/scripts/
chmod 755 /data/adb/dsha/scripts/*.sh
```

---

## 三、 免重启手机直接验证与功能测试指南

由于本模块遵循按需拉起（On-demand）原则，不修改 Android 系统分区（Systemless），也不依赖开机常驻服务，因此**刷入或部署完毕后不需要重启手机**，即可直接在终端中验证全部功能。

### 步骤 1：验证控制脚本与权限
打开手机终端（Termux 或电脑 `adb shell`），切换到 root 权限：
```bash
su
ls -la /data/adb/dsha/scripts/
```
确认 `start.sh`、`stop.sh`、`term.sh`、`status.sh` 四个脚本均在位且具有 `rwxr-xr-x` (755) 可执行权限。

---

### 步骤 2：启动原生 DSH Web 服务
使用 `su -mm`（强制进入全局挂载命名空间）执行启动脚本：
```bash
su -mm -c "/data/adb/dsha/scripts/start.sh 3080"
```
正常启动后，终端将输出：
```text
STATUS:STARTED PID:12345 PORT:3080
BRIDGE_TOKEN:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
==========================================================
进入 Web 鉴权链接 (直接在手机浏览器打开):
http://127.0.0.1:3080/?token=xxxxxxxxxxxxxxxxxxxxxxxxxxxx
==========================================================
```

---

### 步骤 3：验证 WebUI 对话功能
1. **浏览器直接访问**：
   复制终端输出中的 `http://127.0.0.1:3080/?token=...` 链接，在手机任意浏览器（Chrome / Via / Edge 等）粘贴打开，即可直接加载 DSH 前端界面并开始 AI 编程与对话！
2. **配合前端 APK 访问**：
   若已安装 `DSHA-FR` (即 `magisk-apk` 构建的安装包)，直接打开 App 点击「进入对话」，前端将自动识别后台守护进程并秒级载入。

---

### 步骤 4：进入原生 chroot 交互终端测试
```bash
su -mm -c "/data/adb/dsha/scripts/term.sh"
```
进入纯正 Ubuntu 环境后，依次验证运行状态与防砖守卫机制：
```bash
# 1. 确认已获取原生物理 Root 身份
id
# 应显示: uid=0(root) gid=0(root) groups=0(root)

# 2. 确认 Node.js 与包管理环境
node -v   # 显示 v24.19.0
pnpm -v   # 显示可用

# 3. 验证物理块设备屏蔽（关键防砖测试）
ls -la /dev/block
# 目录应为空！由只读 tmpfs 覆盖屏蔽，彻底杜绝误写物理闪存分区导致的变砖风险

# 4. 验证网络与 DNS 解析
curl -I https://www.baidu.com
# 能够正常解析并返回 HTTP 状态

# 退出容器终端
exit
```

---

### 步骤 5：状态检测与安全停止服务
```bash
# 查看运行状态
su -mm -c "/data/adb/dsha/scripts/status.sh"
# 输出: STATUS:RUNNING PID:12345 URL:http://...

# 停止服务并释放资源
su -mm -c "/data/adb/dsha/scripts/stop.sh"
# 输出: STATUS:STOPPED

# 再次核验停止结果
su -mm -c "/data/adb/dsha/scripts/status.sh"
# 输出: STATUS:STOPPED
```

---

### 步骤 6：核验 0 挂载残留（验证 0 开销）
停止后，在宿主终端执行：
```bash
su -c "grep '/data/adb/dsha/rootfs' /proc/mounts"
```
**结果应无任何输出**。表明 `/dev`, `/proc`, `/sys`, `/sdcard`, `/dev/shm`, `/dev/block` 等全部挂载点已被 `stop.sh` 循环干净卸载，文件锁完全释放，后台 0 进程、0 内存开销、0 耗电。

---

## 四、 核心安全守卫机制

在获得物理 Root 的同时，底座提供针对实体手机的双重防护体系：
1. **内核级硬件物理隔离**：
   `start.sh` 和 `term.sh` 会将 `$ROOTFS/dev/block` 挂载为只读、`mode=000` 的空 `tmpfs`，屏蔽物理设备节点，彻底切断底层格盘变砖风险。
2. **命令拦截与 3090 确认桥闭环**：
   容器内预置 `dsh-guard.sh` 与 `dsh-confirm.sh`，默认拦截 `rm -rf /`、`mkfs`、`dd`、`reboot`、`shutdown` 等破坏性命令。执行高危操作时，必须通过 `127.0.0.1:3090/confirm` 由用户在手机屏幕上点击授权后方可执行。

---

## 五、 底包维护与重新打包（开发者）

当你在 chroot 终端内更新了 DSH 核心版本或安装了新的 Linux 工具链后，可通过如下脚本重新导出纯净脱敏的模块刷机包：
```bash
# 在手机终端内进入原生环境
su -mm -c "/data/adb/dsha/scripts/term.sh"

# 执行脱敏打包工具
/sdcard/Download/DSHA/dsha-ksu-project/tools/export-rootfs.sh
```
该工具会自动剔除聊天记录、个人配置、API 密钥、历史命令及缓存，在 `/sdcard/Download/DSHA/dsha-ksu-project/release/` 输出新的刷机包与底包压缩包。
