# DSHA Native (KernelSU / Magisk) 原生 Linux 运行底座与模块

本项目为 **DeepSeek Harness (DSH)** 专为 Android 12+ Root 设备打造的纯原生 Linux (chroot) 模块与底座工程。

彻底弃用 PRoot / Proroot 等用户态 ptrace / LD_PRELOAD 虚拟化方案，将 Ubuntu ARM64 运行时直接部署于手机真实的 `ext4` 分区，实现 **0 虚拟化损耗、原生 glibc 性能、按需启停 0 待机功耗**。

> **💡 是否需要编译？**
> * **本分支（`dsh-magisk`）完全不需要任何编译！**
> * 本分支由纯 Shell 控制脚本、模块元数据与预置好的 Ubuntu RootFS 组成，使用 zip 打包即可刷入使用，无需 Gradle、NDK 或任何编译器。
> * （编译仅适用于 Android 前端客户端分支 `magisk-apk`）。

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

## 二、 底包准备与两种安装部署方式

### 2.1 底包文件（`rootfs.tar.gz`）放在哪里？
为避免模块 zip 刷机包体积过大（数百 MB），本项目推荐采用**“外置底包分离模式”**：模块 zip 仅几 KB（只包含控制脚本），底包独立存放。

* **底包默认存放路径（手机存储）**：
  ```text
  /sdcard/Download/DSHA/rootfs.tar.gz
  ```
  *(注：系统底层路径即 `/data/media/0/Download/DSHA/rootfs.tar.gz`)*
* 将下载好的或导出的 `rootfs.tar.gz` 直接放进上述目录即可，模块安装脚本会自动探测该路径。

---

### 2.2 安装方式 A：使用 KernelSU / APatch / Magisk 管理器刷入
1. **准备文件**：
   * 确保 `/sdcard/Download/DSHA/rootfs.tar.gz` 已就位；
   * 获取模块刷机包 `dsha_ksu_native_v1.2.0.zip`。
2. **刷入安装**：
   * 打开 KernelSU / APatch / Magisk App；
   * 点击「模块」页面的「从本地安装」，选中 `dsha_ksu_native_v1.2.0.zip`；
   * 安装脚本（`customize.sh`）会自动检测并就地将底包解压到 `/data/adb/dsha/rootfs`，并将控制脚本部署至 `/data/adb/dsha/scripts/`。
3. **完成状态**：
   * **安装完成后无需重启手机！**（直接看第三节免重启验证）。

---

### 2.3 安装方式 B：完全不使用管理器，纯命令行手动安装（免刷机包）
如果你不想制作 zip 刷机包，也不想打开 Magisk/KernelSU 管理器，只需在具有 Root 权限的终端（如 Termux 执行 `tsu` 或电脑 `adb shell su`）中执行以下三步：

#### 第一步：创建目录结构
```bash
su
mkdir -p /data/adb/dsha/rootfs /data/adb/dsha/scripts /data/adb/dsha/run
```

#### 第二步：解压底包到物理分区
假设底包放在 `/sdcard/Download/DSHA/rootfs.tar.gz`：
```bash
tar -xzf /sdcard/Download/DSHA/rootfs.tar.gz -C /data/adb/dsha/rootfs
```
*(解压约耗时 15~30 秒，取决于手机闪存读写速度)*

#### 第三步：部署控制脚本并赋权
将本仓库 `magisk-module/scripts/` 下的 4 个脚本复制到目标目录并赋权：
```bash
# 复制控制脚本
cp -rf magisk-module/scripts/* /data/adb/dsha/scripts/

# 赋予 755 可执行权限（必须）
chmod 755 /data/adb/dsha/scripts/*.sh
```
部署即告完成！无需刷入模块，系统同样立即可用。

---

### 2.4 如何停止使用、停用或彻底卸载删除？

* **临时不使用 / 释放内存与功耗**：
  直接执行停止脚本：
  ```bash
  su -mm -c "/data/adb/dsha/scripts/stop.sh"
  ```
  所有进程瞬间退出，挂载点全部卸载，**后台 0 进程、0 内存、0 耗电**。
* **彻底卸载与完全清除（100% 无残留）**：
  先执行停止脚本，然后直接删除目录：
  ```bash
  # 1. 确保服务与挂载点已干净卸载
  su -mm -c "/data/adb/dsha/scripts/stop.sh"

  # 2. 彻底删除运行目录与模块记录
  su -c "rm -rf /data/adb/dsha /data/adb/modules/dsha_native"
  ```
  此时整套 Linux 环境和脚本已被彻底抹除，手机系统恢复如初。

---

## 三、 免重启手机直接验证与功能测试指南

由于本模块遵循按需拉起（On-demand）原则，不修改 Android 系统只读分区（Systemless），所有挂载与进程均由脚本按需接管，因此**刷入或部署完毕后不需要重启手机**，即可直接验证全部功能。

### 步骤 1：验证控制脚本与权限就绪
在终端（Termux 或 `adb shell`）执行：
```bash
su
ls -la /data/adb/dsha/scripts/
```
确认输出中 `start.sh`、`stop.sh`、`term.sh`、`status.sh` 四个脚本均存在且为 `rwxr-xr-x` 权限。

---

### 步骤 2：启动原生 DSH Web 服务并抓取 Token
使用 `su -mm`（必须带 `-mm`，强制进入 Android 全局挂载命名空间）执行：
```bash
su -mm -c "/data/adb/dsha/scripts/start.sh 3080"
```
正常启动后，终端输出形如：
```text
STATUS:STARTED PID:12345 PORT:3080
BRIDGE_TOKEN:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
==========================================================
进入 Web 鉴权链接 (直接在手机浏览器打开):
http://127.0.0.1:3080/?token=xxxxxxxxxxxxxxxxxxxxxxxxxxxx
==========================================================
```

---

### 步骤 3：验证 WebUI 交互与对话
1. **手机浏览器直接访问**：
   复制终端输出中的 `http://127.0.0.1:3080/?token=...` 链接，直接在手机浏览器（Chrome / Via / Edge 等）粘贴打开，秒级进入 DSH Web 交互界面！
2. **配合前端 APK 访问**：
   若安装了 `DSHA-FR` (即 `magisk-apk` 分支构建的轻量安装包)，打开 App 点击「进入对话」，前端会自动探测后台守护进程并秒级载入。

---

### 步骤 4：进入原生 chroot 交互终端测试与安全防砖核验
```bash
su -mm -c "/data/adb/dsha/scripts/term.sh"
```
成功登录纯正 Ubuntu Root 终端后，依次输入：
```bash
# 1. 确认当前为原生物理 Root 身份
id
# 应显示: uid=0(root) gid=0(root) groups=0(root)

# 2. 确认 Node.js 与包管理工具链
node -v   # 显示 v24.19.0
pnpm -v   # 显示版本号

# 3. 验证物理块设备屏蔽（关键防砖测试）
ls -la /dev/block
# 目录应为空！由只读 tmpfs 覆盖屏蔽，彻底杜绝误写物理闪存分区导致的变砖风险

# 4. 验证网络与 DNS 解析连通性
curl -I https://www.baidu.com
# 能够正常解析并返回 HTTP 状态

# 退出容器终端
exit
```

---

### 步骤 5：状态检测与停止服务
```bash
# 查看当前运行状态
su -mm -c "/data/adb/dsha/scripts/status.sh"
# 输出: STATUS:RUNNING PID:12345 URL:http://...

# 停止服务并释放所有挂载与文件锁
su -mm -c "/data/adb/dsha/scripts/stop.sh"
# 输出: STATUS:STOPPED

# 再次确认状态
su -mm -c "/data/adb/dsha/scripts/status.sh"
# 输出: STATUS:STOPPED
```

---

### 步骤 6：核验 0 挂载残留（验证 0 开销）
停止后，在宿主终端执行：
```bash
su -c "grep '/data/adb/dsha/rootfs' /proc/mounts"
```
**结果应无任何输出**。证明 `/dev`, `/proc`, `/sys`, `/sdcard`, `/dev/shm`, `/dev/block` 等所有挂载点已被 `stop.sh` 循环卸载干净，后台 0 进程、0 内存开销、0 耗电。

---

## 四、 核心安全守卫机制

在获得物理 Root 的同时，底座提供针对实体手机的双重防护体系：
1. **内核级硬件物理隔离**：
   `start.sh` 和 `term.sh` 会将 `$ROOTFS/dev/block` 挂载为只读、`mode=000` 的空 `tmpfs`，屏蔽物理设备节点，切断底层格盘变砖风险。
2. **命令拦截与 3090 确认桥闭环**：
   容器内预置 `dsh-guard.sh` 与 `dsh-confirm.sh`，默认拦截 `rm -rf /`、`mkfs`、`dd`、`reboot`、`shutdown` 等破坏性命令。执行高危操作时，必须通过 `127.0.0.1:3090/confirm` 由用户在手机屏幕上点击授权后方可执行。

---

## 五、 本地与云端打包指南

### 5.1 本地一键打包（开发者/手机端）
项目已内置便捷打包脚本 `scripts/build-module.sh`：

* **方式 1：打包轻量版模块（~20KB，外置底包模式，推荐）**
  ```bash
  ./scripts/build-module.sh
  ```
  产物位于 `dist/dsha_ksu_native_lite.zip`，刷入时自动寻找手机存储的 `rootfs.tar.gz`。

* **方式 2：打包全内置完整刷机包（~200MB，内置底包，开箱即用）**
  ```bash
  ./scripts/build-module.sh --full
  ```
  自动寻找本地底包并打包至 `dist/dsha_ksu_native_full.zip`，分发给其他用户无需单独下载底包。

* **方式 3：从当前运行的手机环境导出并打包（脱敏维护）**
  当你在 chroot 终端更新了 Node 依赖或工具链后，直接在 chroot 终端内执行：
  ```bash
  # 终端内执行脱敏导出
  /sdcard/Download/DSHA/dsha-ksu-project/tools/export-rootfs.sh
  ```
  脚本会自动清理缓存、剔除敏感历史，并在 `/sdcard/Download/DSHA/dsha-ksu-project/release/` 输出全新的底包与刷机包。

---

### 5.2 云端自动打包（GitHub Actions CI/CD）
本分支已配置 `.github/workflows/magisk-module-build.yml` 自动化工作流：
1. **触发方式**：
   * 向 `dsh-magisk` 分支执行 `git push`；
   * 或在 GitHub 仓库页面「Actions」→「Package Magisk/KernelSU Native Module」点击「Run workflow」手动触发。
2. **云端产物**：
   * 自动生成轻量版 `dsha_ksu_native_lite.zip` 与全内置完整版 `dsha_ksu_native_full.zip`；
   * 在对应 Actions 运行页面的 **Artifacts（构建产物）** 列表中即可一键下载使用！
