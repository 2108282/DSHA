# DSHA Native (APK + Magisk 核心) 完整打包与发版指南

本项目采用 **双分支并行协同架构**：
- **`magisk-apk`**：Android 前端客户端外壳（纯 64 位，无内置底包，负责 3095 硬件桥与 Web 视图）。
- **`dsh-magisk`**：KernelSU / Magisk 原生 Linux 底座与模块源码（包含 7 大内置插件与控制脚本）。
- **`main`**：主分支，保持与 `magisk-apk` 100% 镜像同步。

---

## 目录
1. [版本号与命名契约规范](#一-版本号与命名契约规范)
2. [方式一：GitHub Actions 云端自动化联合发版（推荐）](#二-方式一github-actions-云端自动化联合发版推荐)
3. [方式二：本地命令行编译与手动打包](#三-方式二本地命令行编译与手动打包)
   - [1. 编译 Android APK (`magisk-apk`)](#31-编译-android-apk-magisk-apk)
   - [2. 打包 Magisk Lite 热更模块 (`dsh-magisk`)](#32-打包-magisk-lite-热更模块-dsh-magisk)
   - [3. 打包 Magisk Full 全内置刷机包 (`dsh-magisk`)](#33-打包-magisk-full-全内置刷机包-dsh-magisk)
   - [4. 从当前真机环境导出新底包打包](#34-从当前真机环境导出新底包打包)
4. [常见避坑与故障速查](#四-常见避坑与故障速查)

---

## 一、 版本号与命名契约规范

发版前必须严格遵循以下版本命名规则，避免打包失败或覆盖安装报错：

| 项目 | 配置文件路径 | 参数与规范 | 示例值 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **APK 显示版本** | `app/build.gradle` | `versionName` (字符串) | `"dsha-for-root 1.0"` | 界面显示版本，对齐当前架构代号 |
| **APK 内部版本** | `app/build.gradle` | `versionCode` (纯整型) | `124` | **严禁写字符串**，必须递增（防止降级覆盖失败） |
| **Magisk 模块版本** | `magisk-module/module.prop` | `version` (字符串) | `0.1.5rc2-u1` | 模块列表显示的版本字符串 |
| **Magisk 内部版本** | `magisk-module/module.prop` | `versionCode` (纯整型) | `120` 或顺延 | 供管理器判断更新 |

---

## 二、 方式一：GitHub Actions 云端自动化联合发版（推荐）

仓库已配置统一联合发布流水线 `.github/workflows/release.yml`，执行一次即可**同时产出 APK 与两款 Magisk 模块并直接上线 Release**。

### 操作步骤：
1. 打开 GitHub 仓库页面，点击顶部 **Actions** 标签；
2. 在左侧工作流列表中选择 **`Joint Release (APK + Magisk Module)`**；
3. 点击右侧 **Run workflow** 下拉菜单：
   - **Use workflow from**：必须选择 **`magisk-apk`**；
   - **Release Tag**：填写版本 Tag（如 `0.1.5rc2-u1` 或 `dsha-for-root-1.0`）；
   - **标记为预发布**：按需勾选（正式发布留空）；
4. 点击绿色 **Run workflow** 按钮启动构建。

### 自动化构建内部执行流程：
```text
[触发构建] ──► 检出 magisk-apk 分支 ──► `./gradlew :app:assembleStandardDebug` ──► 生成 dsha-for-root-1.0.apk
           │
           └──► 检出 dsh-magisk 分支 ──► 打包控制脚本 ─────────────────────────────► 生成 dsha_ksu_native_lite.zip
                                     │
                                     └──► 自动拉取已验证的 rootfs.tar.gz ────────► 生成 dsha_ksu_native_full.zip
                                                                                  │
                                                                                  ▼
                                                            全部资产自动上传至单一 GitHub Release 页面
```

---

## 三、 方式二：本地命令行编译与手动打包

如果你需要在本地工作站或真机环境中离线构建，可按以下步骤操作：

### 3.1 编译 Android APK (`magisk-apk`)

#### 环境要求：
- **JDK**：17（Temurin 17 / OpenJDK 17）
- **Android SDK**：API 31 ~ 37（Build-Tools 36.0.0）
- **Python**：3.9+（构建资产自检依赖）

#### 编译指令：
```bash
# 1. 切换到 magisk-apk 分支
git checkout magisk-apk

# 2. 授予可执行权限并编译 Debug 包
chmod +x gradlew
./gradlew :app:assembleStandardDebug --stacktrace

# 3. 产物提取与重命名
cp app/build/outputs/apk/standard/debug/app-standard-debug.apk dsha-for-root-1.0.apk
sha256sum dsha-for-root-1.0.apk > dsha-for-root-1.0.apk.sha256
```

> **签名提示**：默认打出的 Debug 包使用通用调试证书。如需生成可覆盖线上版本的签名包，可在执行前导出环境变量：
> ```bash
> export DSHA_KEYSTORE="/path/to/dsha.keystore"
> export DSHA_KEYSTORE_PASSWORD="android"
> export DSHA_KEY_ALIAS="androiddebugkey"
> export DSHA_KEY_PASSWORD="android"
> ```

---

### 3.2 打包 Magisk Lite 热更模块 (`dsh-magisk`)

体积仅 **~28 KB**，仅含控制脚本与元数据，适合已安装底包的用户在线热更。

```bash
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
```

---

### 3.3 打包 Magisk Full 全内置刷机包 (`dsh-magisk`)

体积约 **~260 MB**，内置完整脱敏版 Ubuntu ARM64 生产闭包与 7 大原生插件，刷入立即可用。

```bash
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
```

---

### 3.4 从当前真机环境导出新底包打包

若在手机内部通过 `pnpm update` 升级了 DSH 官方核心或修改了内置插件，需重新导出脱敏底包：

在当前手机 root 终端中运行仓库维护脚本：
```bash
su
/sdcard/Download/DSHA/dsha-ksu-project/tools/export-rootfs.sh
```
该脚本会自动完成：
1. 清理 apt 缓存、日志、会话历史与临时文件；
2. 自动排除私人账号、API Key、Cookie 及用户后装的插件（严格脱敏）；
3. 利用 `pigz` 多线程压缩生成纯净 `rootfs.tar.gz`；
4. 自动生成最新的刷机包输出至 `/sdcard/Download/DSHA/dsha_ksu_native_full.zip`。

---

## 四、 常见避坑与故障速查

1. **Magisk 刷机报 `Not a valid zip file` 或找不到 `update-binary`**：
   - **原因**：打包 zip 时把 `magisk-module` 这一层目录打进去了。
   - **规范**：必须在 `magisk-module` 目录下执行 `zip -r ... META-INF module.prop`，确保解开 zip 时第一层直接看到 `META-INF` 和 `module.prop`。
2. **覆盖安装 APK 提示「应用未安装 / 签名不匹配」**：
   - **原因**：本地构建时未指定 `DSHA_KEYSTORE`，AGP 随机生成了本地临时密钥。
   - **规范**：开发调试请先卸载旧包，或统一配置全局 `DSHA_KEYSTORE` 环境变量为官方发布密钥。
3. **APK 编译报 `versionCode` 类型转换错误**：
   - **原因**：把 `versionCode` 写成了带字母的字符串（如 `"dsha-for-root"`）。
   - **规范**：`versionCode` 必须是純数字（如 `124`），字符串写在 `versionName`。
4. **修改代码后同步到 `main` 分支的标准流程**：
   ```bash
   git checkout magisk-apk
   # 修改代码并 commit
   git push origin magisk-apk

   # 强制镜像同步给 main
   git checkout main
   git reset --hard magisk-apk
   git push -f origin main
   ```
