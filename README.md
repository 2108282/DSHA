# DSHA Native (KernelSU / Magisk) 原生 Linux 运行底座与模块

本项目为 **DeepSeek Harness (DSH)** 专为 Android 12+ Root 设备打造的纯原生 Linux (chroot) 模块与底座工程。

彻底弃用 PRoot / Proroot 等用户态 ptrace / LD_PRELOAD 虚拟化方案，将 Ubuntu ARM64 运行时直接部署于手机真实的 `ext4` 分区，实现 **0 虚拟化损耗、原生 glibc 性能、按需启停 0 待机功耗**。

> **💡 架构演进与开发维护指引（核心必读）**：
> * **核心开发铁律（双轨修补原则）**：今后写代码修改时，必须**同时写两套**：
>   1. **面向 Full 完整包**：按 1:1 目录放入 `rootfs-overlay/`，编译底包时直接装进去；
>   2. **面向 Lite 热更新包**：在 `magisk-module/scripts/` 下写一个现场执行的增量 `.sh` 脚本，用户刷 Lite 包时音量键选择现场执行！
> * 打包机制请参阅：👉 **[ARCHITECTURE_AND_PACKAGING.md](./ARCHITECTURE_AND_PACKAGING.md)**；
> * 底包发布至 Release [Tag: `rootfs`](https://github.com/2108282/DSHA/releases/tag/rootfs) 与 [rootfs 分支](https://github.com/2108282/DSHA/tree/rootfs)。

---

## 一、 系统架构与关键路径

```text
dsh-magisk 分支仓库根目录
├── .github/workflows/
│   └── magisk-module-build.yml       # 通用 CI/CD 构建流水线
├── magisk-module/                    # 模块本体与安装器
│   ├── customize.sh                  # 安装入口 (含防变砖安全检查、音量键交互)
│   ├── service.sh                    # 开机守护 (幽灵进程解除限制)
│   └── scripts/                      # start.sh / stop.sh / term.sh 运行时脚本
├── rootfs-overlay/                   # 👈 【核心】：1:1 反射式动态镜像层
│   └── root/
│       ├── dsha-web-mobile/          # 消除手机顶部空白行 + 支持通知审批自动关卡
│       ├── dsha-task-notifier/       # 具备 justApproved 状态机锁，杜绝误弹完成通知
│       ├── dsha-status-overlay/      # 顶部灵动悬浮条插件
│       └── dsha-device-shell-guide/  # 设备 Shell 原生指令提示插件
│       # 【未来无论新增何种插件/补丁，直接丢在这里即可，无需在 CI 声明】
├── scripts/
│   ├── build-module.sh               # 本地一键打包脚本
│   └── publish-rootfs-asset.sh       # 底包发布与持久化分支同步脚本
└── tools/
    └── dynamic-rootfs-merge.sh       # 👈 通用反射式底包合成引擎
```

---

## 二、 手机真实运行路径 (`/data/adb/dsha/`)

```text
/data/adb/dsha/
├── rootfs/                         # 纯原生 Ubuntu ARM64 根文件系统
│   ├── bin/ -> usr/bin
│   ├── usr/local/bin/node          # Node.js 运行时 (v24.19.0)
│   ├── usr/local/bin/dsh           # DSH 核心命令行程序
│   └── root/.dsh/.bridge_token     # 3095 设备硬件能力鉴权 Token
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

## 三、 模块安装使用与免 Magisk 解压部署说明

模块打包产物为单一完整文件：`dsha_ksu_native_full.zip`（内含控制脚本与完整 Ubuntu 底包）。

你可以根据当前设备环境，从以下两种方式中二选一：

### 3.1 方式一：使用 KernelSU / APatch / Magisk 管理器安装（标准卡刷）
适合手机已安装 root 管理器 App 的常规用户：
1. **下载或获取模块包**：将 `dsha_ksu_native_full.zip` 复制到手机存储（如 `/sdcard/Download/`）。
2. **刷入模块**：
   * 打开 KernelSU / APatch / Magisk 管理器；
   * 进入「模块」页面，点击「从本地安装」；
   * 选择 `dsha_ksu_native_full.zip`，刷入脚本（`customize.sh`）会自动就地将底包解压到 `/data/adb/dsha/rootfs` 并配置好所有控制脚本。
3. **完成状态**：
   * **刷入成功后，完全不需要重启手机！**（直接看第四节免重启使用与验证）。

---

### 3.2 方式二：不用 Magisk / KernelSU 管理器，纯命令行手动解压部署
如果你不想打开管理器刷入，或者运行在自定义 Root 环境、电脑 `adb shell` 中，只需通过命令行从模块 zip 包中直接管道解压：

在终端中切换为 root 权限（电脑执行 `adb shell su` 或手机 Termux 执行 `tsu`）：

```bash
su

# 步骤 1：创建底层物理目录
mkdir -p /data/adb/dsha/rootfs /data/adb/dsha/scripts /data/adb/dsha/run

# 步骤 2：直接从模块 zip 包中提取并解压 rootfs（流式管道，无需占用多余临时空间）
# 假设模块文件位于 /sdcard/Download/dsha_ksu_native_full.zip
unzip -p /sdcard/Download/dsha_ksu_native_full.zip rootfs.tar.gz | tar -xz -C /data/adb/dsha/rootfs

# 步骤 3：从模块包提取控制脚本并赋予可执行权限 (755)
unzip -o /sdcard/Download/dsha_ksu_native_full.zip "scripts/*" -d /tmp/dsha_tmp/
cp -rf /tmp/dsha_tmp/scripts/* /data/adb/dsha/scripts/
chmod 755 /data/adb/dsha/scripts/*.sh
rm -rf /tmp/dsha_tmp
```

解压耗时约 15~30 秒。完成后**同样无需重启手机**，环境已完全就绪！

---

### 3.3 如何停止使用或彻底卸载删除？

* **临时不使用 / 彻底释放后台开销**：
  ```bash
  su -mm -c "/data/adb/dsha/scripts/stop.sh"
  ```
  停止后所有挂载点自动解绑，**后台 0 进程、0 内存开销、0 功耗**。
* **彻底卸载与完全清除**：
  ```bash
  # 1. 确保服务与挂载已干净卸载
  su -mm -c "/data/adb/dsha/scripts/stop.sh"

  # 2. 彻底删除运行数据与模块记录
  su -c "rm -rf /data/adb/dsha /data/adb/modules/dsha_native"
  ```
  整套 Linux 环境和脚本即被物理抹除，系统 100% 恢复如初，无残留文件。

---

## 四、 免重启手机直接使用与功能测试指南

由于本模块遵循按需拉起（On-demand）原则，不修改 Android 系统只读分区（Systemless），所有挂载与服务均由控制脚本独立按需接管，因此**安装完成后不需要重启手机**，即可直接在终端中验证全部功能：

### 步骤 1：验证控制脚本与权限就绪
在终端（Termux 或电脑 `adb shell`）执行：
```bash
su
ls -la /data/adb/dsha/scripts/
```
确认输出中 `start.sh`、`stop.sh`、`term.sh`、`status.sh` 四个脚本均存在且权限为 `rwxr-xr-x` (755)。

---

### 步骤 2：启动原生 DSH Web 服务并获取 Token
使用 `su -mm`（必须带 `-mm` 标志，强制挂入 Android 全局挂载命名空间）执行：
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

### 步骤 3：进入 WebUI 交互与对话
1. **浏览器直接访问**：
   复制终端输出中的 `http://127.0.0.1:3080/?token=...` 链接，直接在手机浏览器（Chrome / Via / Edge 等）粘贴打开，秒级进入 DSH Web 交互界面！
2. **配合前端 APK 访问**：
   若安装了 `DSHA-FR` (即 `magisk-apk` 分支构建的轻量前端外壳)，打开 App 点击「进入对话」，前端会自动探测后台守护进程并秒级载入。

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
