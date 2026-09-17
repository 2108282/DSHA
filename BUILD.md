# DSHA Native 架构、构建与自动化发版规范指南 (BUILD.md)

本文档面向 DSHA (KernelSU / Magisk 原生 Linux chroot 极速运行时) 开发者与 CI 自动化流水线，明确规定代码提交规范、本地打包流程、以及 GitHub Actions（`.yml` 工作流）的发版机制。

---

## 一、 分支架构与职责分工契约

DSHA 采用清晰的职责隔离架构，杜绝在单个分支内混合不同平台的源码与庞大资产：

| 分支名称 | 定位与职责 | 产出物 |
| :--- | :--- | :--- |
| **`magisk-apk`** | **Android 前端客户端源码** (Java 17, minSdk 31, 纯 64 位)<br>承载 WebView UI (`:3080`)、3095 硬件能力桥 (`HttpShellService`)、PTY 终端会话连接器，包体约 25MB。 | `DSHA-FR <版本>.apk` |
| **`dsh-magisk`** | **Linux 底座与模块源码**<br>包含控制脚本（`scripts/`）、模块安装器（`customize.sh` / `customize.lite.sh`）、通用增量层叠（`rootfs-overlay/`）。 | `dsha_ksu_native_lite.zip` (~2MB)<br>`dsha_ksu_native_full.zip` (~200MB) |
| **`dsha-for-root.x`** | **统一发版调度分支（流水线收口）**<br>本分支不存放任何业务源码，专职管理 GitHub Actions 联合总装流水线（`.github/workflows/package-*.yml`）。 | 自动拉取上述两分支源码，联合打包并发布 GitHub Releases |

---

## 二、 代码提交与双轨修补规范（核心铁律）

为了使新用户（全量刷机）和老用户（增量热更新）都能无缝获得更新，**代码修改与提交流程必须严格遵循以下规范**：

### 1. 通用增量文件维护（面向 `rootfs-overlay/`）
* **规则**：任何需要在容器 Linux 系统（`/data/adb/dsha/rootfs/`）内新增或修改的文件，**一律放在 `rootfs-overlay/` 对应路径下**。
  - 例如修改移动端样式：修改 `rootfs-overlay/root/dsha-web-mobile/lib/client.js`；
  - 例如新增局域网代理实体：放入 `rootfs-overlay/root/.dsh/dsha-lan-proxy.js`；
  - 例如修改系统解析或 hosts：放入 `rootfs-overlay/etc/resolv.conf`。
* **通用复用机制**：
  - **Full 完整包**：打包引擎（`tools/dynamic-rootfs-merge.sh`）会自动将 `rootfs-overlay/` 动态镜像熔铸入 `rootfs.tar.gz` 底包中；
  - **Lite 增量包**：打包引擎（`scripts/build-module.sh --lite`）会自动将 `rootfs-overlay/` 整体打入 zip；刷入 Lite 模块时，安装器（`customize.lite.sh`）会**零硬编码、通用镜像递归覆盖至老用户容器**并赋予执行权限。
  - **红线约束**：**严禁在安装脚本中针对具体业务文件名进行硬编码！** Lite 包是通用可复用框架，任何容器文件变更均直接走 `rootfs-overlay/` 通用镜像通道。

### 2. 五大基础控制脚本维护（面向 `scripts/` 与 `magisk-module/scripts/`）
* **范围**：`start.sh`、`stop.sh`、`status.sh`、`term.sh`、`lan-proxy.sh`。
* **规则**：
  - 这五大脚本由宿主机 Magisk/KernelSU 直接调度，统一维护在 `scripts/` 与 `magisk-module/scripts/` 中；
  - 刷入 Lite 增量包时，安装器在【第 2 步】通过物理音量键提示用户是否覆盖这五大脚本。

### 3. 现场执行增量补丁（面向 `magisk-module/scripts/patch-*.sh`）
* **场景**：若某次更新需要执行现场初始化或修补（例如初始化解耦的 `.lan_token`、调用 sed 调整配置、重建特殊软链接等）；
* **规则**：
  - 编写独立的 Shell 补丁脚本，存放在 `magisk-module/scripts/` 下，命名为 `patch-<功能>.sh`（例如现有的 `patch-lan-proxy.sh`、`patch-fix-approval-notify.sh`）；
  - 脚本必须自包含、具备容错与幂等性（多次执行不产生副作用）；
  - 必须严格排除五大基础脚本名称；
  - 刷入 Lite 增量包时，安装器在【第 1 步】通过物理音量键提示用户是否现场执行该补丁。

---

## 三、 GitHub Actions 工作流文件规范（`.yml`）

流水线集中在 `dsha-for-root.x` 分支的 `.github/workflows/` 目录下，严格区分**全量发版**与**轻量发版**：

### 1. 全量发版流水线：`.github/workflows/package-all.yml`
* **定位**：对外交付的**全家桶正式大版本**。
* **交付产物（三大件齐全）**：
  1. `DSHA-FR <版本>.apk`（Android 前端安装包）
  2. `dsha_ksu_native_full.zip`（全内置完整刷机模块，含底包，约 200MB）
  3. `dsha_ksu_native_lite.zip`（极速热更新补丁模块，约 2MB）
  4. 对应的 SHA-256 校验和文件。
* **触发标签（Tags）规则**：
  ```yaml
  tags:
    - "v*"
    - "dsha-for-root*"
    - "1.*"
    - "0.*"
    - "!*lite*"
    - "!*Lite*"
  ```
* **核心红线**：
  - **全量打包必须同时生成 Lite 增量包**（方便大版本同步提供补丁更新）；
  - **凡是标签包含 `lite` 或 `Lite`，本工作流必须绝对避让并禁止运行！**

### 2. 轻量发版流水线：`.github/workflows/package-lite.yml`
* **定位**：快速迭代修复、补丁升级的**极速发布通道**。
* **交付产物（两件套）**：
  1. `DSHA-FR <版本>.apk`（Android 前端安装包）
  2. `dsha_ksu_native_lite.zip`（极速热更新补丁模块，约 2MB）
  3. 对应的 SHA-256 校验和文件。
* **触发标签（Tags）规则**：
  ```yaml
  tags:
    - "*lite*"
    - "*Lite*"
  ```
* **核心红线**：
  - **轻量打包严禁编译 Full 完整包，严禁下载 200MB 底包！**
  - 构建产物只保留 APK + Lite，实现秒级编译与极速交付。

---

## 四、 本地一键打包指令（开发者自测）

在 `dsh-magisk` 分支根目录下，执行 `scripts/build-module.sh`：

### 1. 单独打包极速热更新包 (Lite)
```bash
bash scripts/build-module.sh --lite
```
* 产物：`dist/dsha_ksu_native_lite.zip`；
* 特性：自动收集 `scripts/` 控制脚本、增量补丁与 `rootfs-overlay/` 容器增量资产。

### 2. 打包全内置完整包 (Full)
```bash
bash scripts/build-module.sh full
```
* 产物：`dist/dsha_ksu_native_full.zip`；
* 特性：自动检测本地 `rootfs.tar.gz`（若无则自动拉取基准底包），调用 `dynamic-rootfs-merge.sh` 将 `rootfs-overlay/` 深度熔铸入底包。

### 3. 本地模拟流水线总装 (在 `dsha-for-root.x` 分支)
```bash
bash scripts/package-all.sh
```
* 同时检出 `magisk-apk` 与 `dsh-magisk`，并在 `dist/` 下总装输出三大产物及校验和。

---

## 五、 发版操作标准指南 (SOP)

当代码通过验证，需要正式发布时：

1. **发布常规正式版本（产出 APK + Full + Lite 三大件）**：
   ```bash
   git tag 0.1.5rc.2-u3
   git push origin 0.1.5rc.2-u3
   ```
   👉 触发 `package-all.yml`，自动构建三大交付物并同步发布至 Release。

2. **发布轻量补丁版本（产出 APK + Lite 两件套）**：
   ```bash
   git tag 0.1.5rc.2-u3.1-lite
   git push origin 0.1.5rc.2-u3.1-lite
   ```
   👉 触发 `package-lite.yml`，仅构建 APK + Lite 模块，绝不编译 Full 包。
