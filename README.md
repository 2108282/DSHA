# DSHA Native (KernelSU / Magisk) 原生 Linux 运行底座与模块

本项目为 **DeepSeek Harness (DSH)** 专为 Android 12+ Root 设备打造的纯原生 Linux (chroot) 模块与底座工程。

彻底弃用 PRoot / Proroot 等用户态 ptrace / LD_PRELOAD 虚拟化方案，将 Ubuntu ARM64 运行时直接部署于手机真实的 `ext4` 分区，实现 **0 虚拟化损耗、原生 glibc 性能、按需启停 0 待机功耗**。

> **💡 是否需要编译？**
> * **本分支（`dsh-magisk`）完全不需要任何编译！**
> * 本分支由纯 Shell 控制脚本、模块元数据与预置好的 Ubuntu RootFS 组成，直接输出为**单一完整模块刷机包**（`dsha_ksu_native_full.zip`），开箱即用，无需 Gradle、NDK 或任何编译器。
> * （编译仅适用于 Android 前端客户端分支 `magisk-apk`）。

---

## 一、 系统架构与关键路径

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

## 二、 模块安装使用与免 Magisk 解压部署说明

模块打包产物为单一完整文件：`dsha_ksu_native_full.zip`（内含控制脚本与完整 Ubuntu 底包）。

你可以根据当前设备环境，从以下两种方式中二选一：

### 2.1 方式一：使用 KernelSU / APatch / Magisk 管理器安装（标准卡刷）
适合手机已安装 root 管理器 App 的常规用户：
1. **下载或获取模块包**：将 `dsha_ksu_native_full.zip` 复制到手机存储（如 `/sdcard/Download/`）。
2. **刷入模块**：
   * 打开 KernelSU / APatch / Magisk 管理器；
   * 进入「模块」页面，点击「从本地安装」；
   * 选择 `dsha_ksu_native_full.zip`，刷入脚本（`customize.sh`）会自动就地将底包解压到 `/data/adb/dsha/rootfs` 并配置好所有控制脚本。
3. **完成状态**：
   * **刷入成功后，完全不需要重启手机！**（直接看第三节免重启使用与验证）。

---

### 2.2 方式二：不用 Magisk / KernelSU 管理器，纯命令行手动解压部署
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

### 2.3 如何停止使用或彻底卸载删除？

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

## 三、 免重启手机直接使用与功能测试指南

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

---

## 四、 本地与云端打包指南

### 4.1 本地一键打包完整模块包
项目已内置便捷打包脚本 `scripts/build-module.sh`：
```bash
# 直接生成单一完整刷机包 (包含 rootfs 底包与全部控制脚本)
./scripts/build-module.sh --full
```
产物位于 `dist/dsha_ksu_native_full.zip`（或 `/sdcard/Download/DSHA/dsha_ksu_native_full.zip`），体积约 310MB，分发给其他用户无需单独下载底包，直接刷入或解压即可使用。

DSHA Native (APK + Magisk 核心) 完整打包与发版指南
本项目采用 双分支并行协同架构：

magisk-apk：Android 前端客户端外壳（纯 64 位，无内置底包，负责 3095 硬件桥与 Web 视图）。
dsh-magisk：KernelSU / Magisk 原生 Linux 底座与模块源码（包含 7 大内置插件与控制脚本）。
main：主分支，保持与 magisk-apk 100% 镜像同步。
目录
版本号与命名契约规范
方式一：GitHub Actions 云端自动化联合发版（推荐）
方式二：本地命令行编译与手动打包
1. 编译 Android APK (magisk-apk)
2. 打包 Magisk Lite 热更模块 (dsh-magisk)
3. 打包 Magisk Full 全内置刷机包 (dsh-magisk)
4. 从当前真机环境导出新底包打包
常见避坑与故障速查
一、 版本号与命名契约规范
发版前必须严格遵循以下版本命名规则，避免打包失败或覆盖安装报错：

项目	配置文件路径	参数与规范	示例值	说明
APK 显示版本	app/build.gradle	versionName (字符串)	"dsha-for-root 1.0"	界面显示版本，对齐当前架构代号
APK 内部版本	app/build.gradle	versionCode (纯整型)	124	严禁写字符串，必须递增（防止降级覆盖失败）
Magisk 模块版本	magisk-module/module.prop	version (字符串)	0.1.5rc2-u1	模块列表显示的版本字符串
Magisk 内部版本	magisk-module/module.prop	versionCode (纯整型)	120 或顺延	供管理器判断更新
二、 方式一：GitHub Actions 云端自动化联合发版（推荐）
仓库已配置统一联合发布流水线 .github/workflows/release.yml，执行一次即可同时产出 APK 与两款 Magisk 模块并直接上线 Release。

操作步骤：
打开 GitHub 仓库页面，点击顶部 Actions 标签；
在左侧工作流列表中选择 Joint Release (APK + Magisk Module)；
点击右侧 Run workflow 下拉菜单：
Use workflow from：必须选择 magisk-apk；
Release Tag：填写版本 Tag（如 0.1.5rc2-u1 或 dsha-for-root-1.0）；
标记为预发布：按需勾选（正式发布留空）；
点击绿色 Run workflow 按钮启动构建。
自动化构建内部执行流程：
text
复制
[触发构建] ──► 检出 magisk-apk 分支 ──► `./gradlew :app:assembleStandardDebug` ──► 生成 dsha-for-root-1.0.apk
           │
           └──► 检出 dsh-magisk 分支 ──► 打包控制脚本 ─────────────────────────────► 生成 dsha_ksu_native_lite.zip
                                     │
                                     └──► 自动拉取已验证的 rootfs.tar.gz ────────► 生成 dsha_ksu_native_full.zip
                                                                                  │
                                                                                  ▼
                                                            全部资产自动上传至单一 GitHub Release 页面
三、 方式二：本地命令行编译与手动打包
如果你需要在本地工作站或真机环境中离线构建，可按以下步骤操作：

3.1 编译 Android APK (magisk-apk)
环境要求：
JDK：17（Temurin 17 / OpenJDK 17）
Android SDK：API 31 ~ 37（Build-Tools 36.0.0）
Python：3.9+（构建资产自检依赖）
编译指令：
bash
复制
# 1. 切换到 magisk-apk 分支
git checkout magisk-apk

# 2. 授予可执行权限并编译 Debug 包
chmod +x gradlew
./gradlew :app:assembleStandardDebug --stacktrace

# 3. 产物提取与重命名
cp app/build/outputs/apk/standard/debug/app-standard-debug.apk dsha-for-root-1.0.apk
sha256sum dsha-for-root-1.0.apk > dsha-for-root-1.0.apk.sha256
签名提示：默认打出的 Debug 包使用通用调试证书。如需生成可覆盖线上版本的签名包，可在执行前导出环境变量：

bash
复制
export DSHA_KEYSTORE="/path/to/dsha.keystore"
export DSHA_KEYSTORE_PASSWORD="android"
export DSHA_KEY_ALIAS="androiddebugkey"
export DSHA_KEY_PASSWORD="android"
3.2 打包 Magisk Lite 热更模块 (dsh-magisk)
体积仅 ~28 KB，仅含控制脚本与元数据，适合已安装底包的用户在线热更。

bash
复制
# 1. 切换到 dsh-magisk 分支
git checkout dsh-magisk

# 2. 进入模块源码目录并配置脚本权限
cd magisk-module
chmod +x customize.sh service.sh action.sh uninstall.sh scripts/*.sh

# 3. 纯净打包（顶级目录直接包含 META-INF 与 module.prop）
zip -r9 ../dsha_ksu_native_lite.zip \
  META-INF module.prop customize.sh service.sh action.sh uninstall.sh scripts

# 4. 生成校验码
cd ..
sha256sum dsha_ksu_native_lite.zip > dsha_ksu_native_lite.zip.sha256
3.3 打包 Magisk Full 全内置刷机包 (dsh-magisk)
体积约 ~260 MB，内置完整脱敏版 Ubuntu ARM64 生产闭包与 7 大原生插件，刷入立即可用。

bash
复制
# 1. 在 magisk-module 目录下准备好经过测试的 rootfs.tar.gz 底包
# （若本地无底包，可直接从 release 下载纯净底包放入 magisk-module/）
curl -L -f -o magisk-module/rootfs.tar.gz \
  https://github.com/2108282/DSHA/releases/download/0.1.5rc1-base/rootfs.tar.gz

# 2. 执行打包（注意：对已压缩的 rootfs 采用 -0 存储模式，避免 CPU 重复压缩耗时）
cd magisk-module
chmod +x customize.sh service.sh action.sh uninstall.sh scripts/*.sh
zip -r0 ../dsha_ksu_native_full.zip \
  META-INF module.prop customize.sh service.sh action.sh uninstall.sh scripts rootfs.tar.gz

# 3. 生成校验码
cd ..
sha256sum dsha_ksu_native_full.zip > dsha_ksu_native_full.zip.sha256
3.4 从当前真机环境导出新底包打包
若在手机内部通过 pnpm update 升级了 DSH 官方核心或修改了内置插件，需重新导出脱敏底包：

在当前手机 root 终端中运行仓库维护脚本：

bash
复制
su
/sdcard/Download/DSHA/dsha-ksu-project/tools/export-rootfs.sh
该脚本会自动完成：

清理 apt 缓存、日志、会话历史与临时文件；
自动排除私人账号、API Key、Cookie 及用户后装的插件（严格脱敏）；
利用 pigz 多线程压缩生成纯净 rootfs.tar.gz；
自动生成最新的刷机包输出至 /sdcard/Download/DSHA/dsha_ksu_native_full.zip。
四、 常见避坑与故障速查
Magisk 刷机报 Not a valid zip file 或找不到 update-binary：
原因：打包 zip 时把 magisk-module 这一层目录打进去了。
规范：必须在 magisk-module 目录下执行 zip -r ... META-INF module.prop，确保解开 zip 时第一层直接看到 META-INF 和 module.prop。
覆盖安装 APK 提示「应用未安装 / 签名不匹配」：
原因：本地构建时未指定 DSHA_KEYSTORE，AGP 随机生成了本地临时密钥。
规范：开发调试请先卸载旧包，或统一配置全局 DSHA_KEYSTORE 环境变量为官方发布密钥。
APK 编译报 versionCode 类型转换错误：
原因：把 versionCode 写成了带字母的字符串（如 "dsha-for-root"）。
规范：versionCode 必须是純数字（如 124），字符串写在 versionName。
修改代码后同步到 main 分支的标准流程：
bash
复制
git checkout magisk-apk
# 修改代码并 commit
git push origin magisk-apk

# 强制镜像同步给 main
git checkout main
git reset --hard magisk-apk
git push -f origin main
