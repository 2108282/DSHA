# DSHA for Root CI 持续交付与打包总装分支 (`dsha-for-root.x`)

> **架构定位与职责契约**：  
> 本分支为 **统一 CI 自动化流水线调度与交付发布分支**，**本分支不存放任何业务源码**。  
> 专职调度、总装并对齐以下两个业务源分支：
> 1. **前端客户端源码分支**：`magisk-apk`（Android Java 17，纯 64 位原生 WebView 容器与硬件桥）
> 2. **核心底座与模块分支**：`dsh-magisk`（KernelSU / Magisk 原生 Linux chroot uid=0 运行时底座）

---

## 一、 核心交付物矩阵

根据不同发布场景，流水线产出严格标准化的交付物清单：

| 交付物名称 | 产物类型 | 源码源头 | 包含流水线 | 作用与适用场景 |
| :--- | :---: | :---: | :---: | :--- |
| **`DSHA-FR <版本>.apk`** | Android 前端应用 | `magisk-apk` | **全量 & 轻量** | 原生 Android Root 客户端。承载全屏沉浸 UI、3095 端口硬件通道桥 (`HttpShellService`)、PTY 终端连接器与通知/审批通道。 |
| **`dsha_ksu_native_lite.zip`** | 模块极速热更新包 | `dsh-magisk` | **全量 & 轻量** | **通用增量热更新补丁包**（~2MB）。包含通用 `rootfs-overlay/` 镜像层叠、`patches/` 现场补丁与 5 大控制脚本。老用户无需重新下载 200MB 底包，秒级热修。 |
| **`dsha_ksu_native_full.zip`** | 模块全内置完整包 | `dsh-magisk` | **仅全量** | **全内置开箱即刷包**（~218MB）。包含纯净 Ubuntu ARM64 生产闭包与经由 `rootfs-overlay/` 反射熔铸的核心插件，专供新装机开箱即用。 |

---

## 二、 核心发版流水线规范 (`.github/workflows/`)

为了彻底杜绝轻量发布误编译全量底包、以及全量发布遗漏轻量补丁的痛点，工程建立了**两套严格互斥、各司其职的 GitHub Actions 流水线**：

```text
.github/workflows/
├── package-all.yml   # 【全量发版流水线】必须构建三大件：APK + Full 完整包 + Lite 补丁包
└── package-lite.yml  # 【轻量发版流水线】专职极速热修：仅构建两件套 (APK + Lite)，严禁编译 Full 包
```

### 1. 全量发版流水线：`package-all.yml`
* **定位**：对外交付的**全家桶正式大版本**。
* **交付物（三大件齐全）**：
  1. `DSHA-FR <版本>.apk`
  2. `dsha_ksu_native_full.zip`（含全量底包）
  3. `dsha_ksu_native_lite.zip`（同步提供增量补丁）
  4. 对应 SHA-256 校验和清单。
* **触发标签（Git Tag）规则**：
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
  - **全量打包必须同时生成 Lite 增量包**（方便大版本同步提供快速补丁更新）；
  - **凡是标签包含 `lite` 或 `Lite`，本工作流必须绝对避让并禁止运行！**

### 2. 轻量发版流水线：`package-lite.yml`
* **定位**：快速迭代修复、控制脚本升级或插件补丁的**极速发布通道**。
* **交付物（纯两件套）**：
  1. `DSHA-FR <版本>.apk`
  2. `dsha_ksu_native_lite.zip`
  3. 对应 SHA-256 校验和清单。
* **触发标签（Git Tag）规则**：
  ```yaml
  tags:
    - "*lite*"
    - "*Lite*"
  ```
* **核心红线**：
  - **轻量打包严禁编译 Full 完整包，绝对严禁去下载 200MB 底包！** 真正实现秒级流水线编译与低带宽快速交付。

---

## 三、 增量热更新机制与产物一致性闭环保障

为了确保老用户刷入 Lite 增量包后，与全新刷入 Full 包的最终运行状态 **100% 字节级一致**，工程在目录架构与安装逻辑上进行了彻底的规范化重构：

### 1. 物理目录三权分立
* **`scripts/`（控制脚本目录）**：
  - 仅存放宿主机日常管理所需的 5 大基础控制脚本（`start.sh / stop.sh / status.sh / term.sh / lan-proxy.sh`）；
  - Full 包与 Lite 包均会打包；Full 包中**绝对不包含任何增量补丁**，确保宿主机目录极致纯净。
* **`patches/`（现场增量补丁目录）**：
  - 专职存放现场修复脚本（如 `patch-lan-proxy.sh`、`patch-fix-approval-notify.sh`）；
  - **专属于 Lite 增量包，Full 完整包绝对不打包**；脚本具备自愈能力（如直接运行也能自动补齐宿主脚本与 Token）。
* **`rootfs-overlay/`（容器通用增量镜像层叠）**：
  - 零硬编码：任何需要在容器内新增或修改的文件（如移动端样式、插件代码、`dsha-lan-proxy.js`），一律放入此目录；
  - Full 包通过 `dynamic-rootfs-merge.sh` 动态熔铸入底包；Lite 包打包并通用镜像覆盖。

### 2. Lite 模块刷入时的受控两步交互 (`customize.lite.sh`)
用户刷入 Lite 模块时，通过物理音量键进行确定性控制：

```text
=========================================
      DSHA Native 极速热更新补丁包       
=========================================

【第 1 步】：是否应用增量补丁与容器更新？
-----------------------------------------
【音量 +】: 是 (应用增量更新)
【音量 -】: 否 (跳过，不修改容器)
-----------------------------------------
👉 若按【音量 +】：
   1. [通用层叠] 递归镜像覆盖 rootfs-overlay/ 至 /data/adb/dsha/rootfs/ 并保障权限；
   2. [现场补丁] 扫描执行 patches/*.sh 补丁脚本，完成自愈与 Token 初始化。
👉 若按【音量 -】：
   完全跳过！容器内部保持原样，绝对不碰老用户的容器数据。


【第 2 步】：是否覆盖五大基础控制脚本？(start/stop/status/term/lan-proxy)
-----------------------------------------
【音量 +】: 是 (覆盖基础脚本)
【音量 -】: 否 (保留当前已有脚本)
-----------------------------------------
👉 若按【音量 +】：
   把最新的 5 个核心控制脚本覆盖至宿主机 /data/adb/dsha/scripts/ 并赋权 755。
👉 若按【音量 -】：
   保留当前已有控制脚本。
```

---

## 四、 本地总装与测试命令

若需要在本地模拟 GitHub Actions 流水线总装，可在本分支根目录下直接运行：

```bash
# 给予脚本执行权限
chmod +x scripts/*.sh

# 本地一键总装测试（拉取 magisk-apk 与 dsh-magisk 并生成三大件与校验和）
bash scripts/package-all.sh
```

---

## 五、 发版操作速查指引

1. **发布常规正式大版本（产出三大件：APK + Full + Lite）**：
   ```bash
   git tag 0.1.5rc.2-u3
   git push origin 0.1.5rc.2-u3
   ```
   👉 触发 `package-all.yml`，生成完整三大资产。

2. **发布极速补丁版本（产出两件套：APK + Lite 补丁）**：
   ```bash
   git tag 0.1.5rc.2-u3.1-lite
   git push origin 0.1.5rc.2-u3.1-lite
   ```
   👉 触发 `package-lite.yml`，秒级产出轻量交付物，不编译 Full 包。
