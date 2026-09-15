# DSHA Native 构建与打包说明

DSHA 现已全面下沉重构为 **KernelSU / Magisk 原生 Linux chroot (uid=0) 极速运行时**，废弃了 PRoot 虚拟化模拟层。

工程采用 **双分支并行协同架构**：
- **`magisk-apk`**：Android 前端客户端源码（纯原生 64 位，体积仅 ~17MB，无内置底包负担）。
- **`dsh-magisk`**：KernelSU / Magisk 原生 Linux 底座与模块打包（包含 7 大内置插件与控制脚本）。
- **`main`**：默认主分支，与 `magisk-apk` 保持同步。

---

## 一、 Android APK 客户端构建 (`magisk-apk` / `main`)

### 1. 环境要求
| 组件 | 版本要求 | 说明 |
|---|---|---|
| JDK | **17** | OpenJDK 17 或 Temurin 17 |
| Android SDK | **API 31 ~ 37** | 编译 SDK: 37，最低支持: Android 11 (API 31) |
| 架构支持 | **arm64-v8a** | 纯 64 位原生架构 |
| Python | **3.9+** | 负责预构建资产校验与生成 |

### 2. 本地构建指令
```bash
# 给予 gradlew 执行权限
chmod +x gradlew

# 构建标准 Debug APK
./gradlew :app:assembleStandardDebug --stacktrace
```
产物输出路径：`app/build/outputs/apk/standard/debug/app-standard-debug.apk`。

### 3. 发布签名配置（可选）
如果需要打与历史版本签名对齐的正式包，可配置环境变量：
- `DSHA_KEYSTORE`：Keystore 绝对路径
- `DSHA_KEYSTORE_PASSWORD`：密码
- `DSHA_KEY_ALIAS`：密钥别名
- `DSHA_KEY_PASSWORD`：密钥密码

---

## 二、 Magisk / KernelSU 模块打包 (`dsh-magisk`)

模块源码位于仓库 `magisk-module/` 目录：
```text
magisk-module/
├── META-INF/com/google/android/
│   ├── update-binary           # Magisk 通用安装入口
│   └── updater-script          # 标准占位
├── module.prop                 # 模块元数据（ID: dsha_native, 版本）
├── customize.sh                # 安装时解压与权限初始化
├── service.sh                  # 开机服务（解除 Android 12+ 幽灵进程上限）
├── action.sh                   # 模块控制与状态面板
├── uninstall.sh                # 卸载自清理
└── scripts/                    # start.sh / stop.sh / term.sh / status.sh
```

### 1. 打包轻量热更模块 (Lite)
```bash
cd magisk-module
chmod +x customize.sh service.sh action.sh uninstall.sh scripts/*.sh
zip -r9 ../dsha_ksu_native_lite.zip META-INF module.prop customize.sh service.sh action.sh uninstall.sh scripts
```

### 2. 打包全内置刷机模块 (Full)
全量包需在 `magisk-module/` 下放入验证过的 `rootfs.tar.gz`：
```bash
cd magisk-module
zip -r0 ../dsha_ksu_native_full.zip META-INF module.prop customize.sh service.sh action.sh uninstall.sh scripts rootfs.tar.gz
```

---

## 三、 GitHub Actions 自动化联合发布

在 GitHub 仓库中，进入 Actions 页面选择 **Joint Release (APK + Magisk Module)**，点击 **Run workflow** 即可同时完成 APK 编译与 Magisk 模块打包，并在同一个 GitHub Release 页面中自动发布全部资产。
