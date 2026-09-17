# DSHA Magisk 原生模块工程架构与打包发布完全手册

> **致未来的维护者 / AI 协同 Agent（核心必读）**：  
> 本工程是 **DSHA 原生 Linux (KernelSU / Magisk) 核心运行环境**。  
> 请务必完整阅读本手册！特别注意第三部分的**【核心开发铁律：双轨修补原则】**。今后无论新增、修改任何代码或功能，必须严格遵守该规范！

---

## 一、 核心架构设计：反射式动态镜像层（Reflection-Based Overlay）

为了彻底摆脱“每次加新文件、改新插件都要改构建脚本”的硬编码维护窘境，本项目引入了**「文件系统反射式动态层叠引擎（Convention Over Configuration）」**。

### 1. 目录规范与职责分工

```text
dsh-magisk 分支仓库根目录
├── .github/workflows/
│   └── magisk-module-build.yml       # 通用持续集成流水线 (CI/CD: 同时产出 Full 包与 Lite 包)
├── magisk-module/                    # Magisk / KernelSU 模块本体
│   ├── META-INF/                     # 刷机脚本入口
│   ├── module.prop                   # 模块元数据 (版本号、名称、描述)
│   ├── customize.sh                  # Full 包完整安装器 (含底包解压与音量键覆盖确认)
│   ├── customize.lite.sh             # Lite 包两步更新安装器 (音量键控制：第1步执行补丁，第2步覆盖脚本)
│   ├── service.sh                    # 开机守护 (幽灵进程解除限制)
│   ├── action.sh / uninstall.sh      # 操作按钮与卸载清理
│   └── scripts/                      # 运行时控制脚本与增量补丁脚本
│       ├── start.sh                  # 【基础脚本】服务启动与挂载
│       ├── stop.sh                   # 【基础脚本】服务停止与卸载
│       ├── status.sh                 # 【基础脚本】状态与 Token 探针
│       ├── term.sh                   # 【基础脚本】进入纯 Root 终端
│       └── [你的增量补丁.sh]          # 👈 【Lite增量补丁】：任意非基础脚本，Lite刷入时现场执行！
├── rootfs-overlay/                   # 👈 【Full底包直接装入层】：1:1 反射式动态镜像层
│   └── root/
│       ├── dsha-web-mobile/          # 手机端前端源码（消除空白行 + 通知审批关卡）
│       ├── dsha-task-notifier/       # 通知插件（含 justApproved 状态机锁）
│       ├── dsha-status-overlay/      # 顶部灵动悬浮条插件
│       └── dsha-device-shell-guide/  # 设备 Shell 原生指令提示插件
├── scripts/
│   ├── build-module.sh               # 本地打包脚本 (支持默认 Full 与 --lite 独立打包)
│   └── publish-rootfs-asset.sh       # 底包 Release 发布与分支同步脚本
└── tools/
    └── dynamic-rootfs-merge.sh       # 👈 通用动态镜像层叠合成引擎
```

---

## 二、 动态合成引擎的工作原理 (`tools/dynamic-rootfs-merge.sh`)

引擎只做通用的 1:1 递归镜像映射与语法安全断言，**内部零硬编码业务文件名**：

1. **原料解压**：下载并解压基准底包（`0.1.5rc.2-base/rootfs.tar.gz`）；
2. **通用物理净化**：自动清理旧系统遗留标记、缓存与失效补丁；
3. **动态反射镜像叠加**：
   - 遍历 `rootfs-overlay/` 下的所有文件与目录，**1:1 精准覆盖**到目标系统同名路径；
   - **自动注册立牌**：检测到 `/root/dsha-*` 实体，自动创建 `/root/dsha-*-installed` 凭证；
   - **软链自动对齐**：自动在 Web Profile 和全局 `node_modules` 下补齐软链接；
   - **语法安全卫士**：自动对所有插件的 `lib/*.js` 执行 `node --check` 语法断言，带毒代码立即中断并掐断打包；
4. **生成产物**：重新压制输出全新的纯净 `rootfs.tar.gz`。

---

## 三、 【核心开发铁律】：双轨修补原则（重要！必须严格遵守）

无论是修复 Bug、修改前端页面，还是新增系统配置，**今后开发者 / AI 必须同时写两套修补逻辑**：

### 🎯 为什么要双轨？
* **新用户 / 完整刷机用户（Full 包）**：直接刷 200MB 的完整底包，代码必须**在打包编译时直接装入底包**，解压即生效；
* **老用户 / 已有环境热更新用户（Lite 包）**：不想重新刷 200MB 大包，代码必须**作为一个可执行的 `.sh` 脚本放进 `magisk-module/scripts/`**，刷 Lite 小包时现场执行，对已有系统增量打补丁！

---

### 📝 双轨编写标准操作指引：

#### 轨道 1：写能够“在打包时直接装进去”的代码（面向 Full 包）
- **存放位置**：`rootfs-overlay/`
- **规则**：保持与系统真实路径 1:1 对应。
  - 例如修改前端样式：直接修改 `rootfs-overlay/root/dsha-web-mobile/lib/client.js`；
  - 例如修改系统 hosts：直接放入 `rootfs-overlay/etc/hosts`；
- **效果**：云端流水线编译 Full 包时，引擎会自动把这些文件熔铸到底包原位置中，全新刷机者开箱即带。

#### 轨道 2：写一个“单独用来 Lite 现场执行”的脚本（面向 Lite 包）
- **存放位置**：`magisk-module/scripts/`
- **命名规范**：任意合法的 `.sh` 文件名（**绝对不能叫** `start.sh`、`stop.sh`、`status.sh`、`term.sh`），例如 `patch-fix-web.sh` 或 `update-custom-env.sh`；
- **编写规范**：必须写成标准的 Shell 执行脚本，安装器会给它传入 `$ROOTFS_DIR`（宿主下的 chroot 根目录路径，即 `/data/adb/dsha/rootfs`）和 `$DATA_DIR`（`/data/adb/dsha`）：
  ```bash
  #!/system/bin/sh
  # 示例增量补丁脚本：magisk-module/scripts/patch-update-something.sh
  set -euo pipefail
  ROOTFS="${1:-/data/adb/dsha/rootfs}"

  echo "==> 正在对已有环境执行增量热更新..."
  # 在这里写你需要在手机现场执行的操作，例如修改文件、追加配置、修权限等：
  # sed -i 's/old/new/g' "$ROOTFS/etc/some.conf"
  # chmod 644 "$ROOTFS/..."
  echo "✓ 增量热更新执行完成！"
  ```
- **效果**：刷入 Lite 包时，用户在【第 1 步】按【音量 +】，安装器就会**自动现场调用 `sh` 执行这个脚本**，老用户无需刷底包瞬间热修完毕！

---

## 四、 Lite 热更新包刷入时的两步交互机制

用户在 KernelSU / APatch / Magisk 刷入 `dsha_ksu_native_lite.zip`（仅几十 KB）时，`customize.lite.sh` 按照以下两步执行物理音量键选择：

```text
=========================================
      DSHA Native 极速热更新补丁包
=========================================

【第 1 步】：是否执行增量补丁？
-----------------------------------------
请在 15 秒内按手机物理音量键选择：
【音量 +】: 是 (执行增量补丁)
【音量 -】: 否 (跳过，不执行)
-----------------------------------------
👉 若按【音量 +】：
   安装器自动扫描 scripts/ 下除 5 大基础控制脚本外的所有 *.sh 增量补丁，
   赋予执行权限并现场逐个运行：sh "$patch" "$ROOTFS_DIR" "$DATA_DIR"
   现场把你的补丁脚本跑一遍！
👉 若按【音量 -】：跳过，不执行任何补丁。


【第 2 步】：是否覆盖五大基础控制脚本？(start/stop/status/term/lan-proxy)
-----------------------------------------
请在 15 秒内按手机物理音量键选择：
【音量 +】: 是 (覆盖基础脚本)
【音量 -】: 否 (保留当前已有脚本)
-----------------------------------------
👉 若按【音量 +】：
   把最新的 start.sh / stop.sh / status.sh / term.sh / lan-proxy.sh
   覆盖写入手机的 /data/adb/dsha/scripts/ 并 chmod 755。
👉 若按【音量 -】：跳过，完全不动用户手机原有的这 4 个脚本。

=========================================
✓ DSHA 极速热更新全部处理完成！
=========================================
```

---

## 五、 本地与云端编译打包指令

### 1. 本地一键打包（执行 `scripts/build-module.sh`）
- **打包 Full 完整包**（含底包，开箱即刷，~218MB）：
  ```bash
  bash scripts/build-module.sh
  ```
  *(若本地无底包，会自动从 Release `0.1.5rc.2-base` 下载纯净原料并自动动态熔铸)*
- **打包 Lite 热更新包**（仅脚本与补丁，~15KB）：
  ```bash
  bash scripts/build-module.sh --lite
  ```

### 2. 云端自动化构建
每次 push 到 `dsh-magisk` 分支，GitHub Actions（`.github/workflows/magisk-module-build.yml`）会自动并发产出：
1. **`dsha_patch_updater_lite` (即 `dsha_ksu_native_lite.zip`)**：纯脚本增量热更新包；
2. **`dsha_ksu_native_full` (`dsha_ksu_native_full.zip`)**：全内置完整刷机包；
3. **底包 Release 与分支持久化**：自动同步更新 Release 直链与「0.1.5rc.2底包」分支分卷。
