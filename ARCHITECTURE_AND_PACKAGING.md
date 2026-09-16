# DSHA Magisk 原生模块工程架构与打包发布完全手册

> **致未来的维护者 / AI 协同 Agent**：  
> 本工程是 **DSHA 原生 Linux (KernelSU / Magisk) 核心运行环境**。  
> 请务必完整阅读本手册，它详细记录了本项目的目录结构、动态补丁引擎工作原理、日常开发修改规范以及云端打包与发布流程。

---

## 一、 核心架构设计：反射式动态镜像层（Reflection-Based Overlay）

为了彻底摆脱“每次加新文件、改新插件都要改构建脚本”的硬编码维护窘境，本项目引入了**「文件系统反射式动态层叠引擎（Convention Over Configuration）」**。

### 1. 目录规范与职责分工

```text
dsh-magisk 分支仓库根目录
├── .github/workflows/
│   └── magisk-module-build.yml       # 通用持续集成流水线 (CI/CD)
├── magisk-module/                    # Magisk / KernelSU 模块本体
│   ├── META-INF/                     # 刷机脚本入口
│   ├── module.prop                   # 模块元数据 (版本号、名称、描述)
│   ├── customize.sh                  # 安装入口 (含防变砖安全检查、音量键交互)
│   ├── service.sh                    # 开机守护 (幽灵进程解除限制)
│   ├── action.sh / uninstall.sh      # 操作按钮与卸载清理
│   └── scripts/                      # start.sh / stop.sh / term.sh 运行时控制
├── rootfs-overlay/                   # 👈 【核心】：1:1 反射式动态镜像层
│   └── root/
│       ├── dsha-web-mobile/          # 消除手机顶部空白行 + 支持通知审批自动关卡
│       ├── dsha-task-notifier/       # 具备 justApproved 状态机锁，杜绝误弹完成通知
│       ├── dsha-status-overlay/      # 顶部灵动悬浮条插件
│       └── dsha-device-shell-guide/  # 设备 Shell 原生指令提示插件
│       # 【未来无论新增何种插件/补丁，直接丢在这里即可，无需声明】
├── scripts/
│   ├── build-module.sh               # 本地一键快速打包脚本
│   └── publish-rootfs-asset.sh       # 底包 Release 发布与分支同步脚本
└── tools/
    └── dynamic-rootfs-merge.sh       # 👈 【核心】：通用动态镜像层叠合成引擎
```

---

## 二、 动态合成引擎的工作原理 (`tools/dynamic-rootfs-merge.sh`)

无论是本地打包还是 GitHub Actions 云端流水线，均由该引擎统一驱动，分为 4 个原子阶段：

1. **原料解压**：下载并解压基准底包（`0.1.5rc.2-base/rootfs.tar.gz`）；
2. **通用物理净化**：自动清理历史 PRoot 遗留文件、临时缓存与旧补丁脚本；
3. **动态反射镜像叠加**：
   - 遍历 `rootfs-overlay/` 下的所有文件与目录，**1:1 精准覆盖**到目标系统的对应路径（含隐藏文件）；
   - **自动注册立牌**：自动检测 `/root/dsha-*` 实体，为其生成 `/root/dsha-*-installed` 凭证；
   - **软链自动对齐**：自动在 Web Profile (`/root/.dsh/profiles/web/node_modules/`) 和全局 (`/usr/local/lib/node_modules/`) 创建软链接；
   - **语法安全断言**：自动递归扫描所有插件的 `lib/*.js` 并执行 `node --check`，一旦存在语法错误立即中断并告警，绝不打包带毒代码；
4. **生成产物**：压缩输出全新纯净的 `rootfs.tar.gz`。

---

## 三、 日常开发与维护场景操作指南

### 场景 1：我要修改前端样式或修复某个插件的逻辑
1. 直接在 `rootfs-overlay/root/对应插件/`（例如 `rootfs-overlay/root/dsha-web-mobile/lib/client.js`）修改代码；
2. 本地执行语法检查：`node --check rootfs-overlay/root/dsha-web-mobile/lib/client.js`；
3. 提交并推送到 GitHub：
   ```bash
   git add rootfs-overlay/
   git commit -m "fix(web): 优化手机端样式"
   git push origin dsh-magisk
   ```
4. **完全无需修改任何构建脚本**，云端 Actions 会自动识别、自动校验、自动熔铸打包出最新刷机包！

### 场景 2：我要新增一个全新插件或系统配置文件
1. 新建插件目录，比如 `rootfs-overlay/root/dsha-my-new-plugin/`；
2. 放入 `package.json`、`cordis.patch.yml` 以及 `lib/index.js`；
3. 如果需要注入系统配置文件，直接建立对应路径即可（例如 `rootfs-overlay/etc/my-config.conf`）；
4. `git push` 后，引擎自动扫描到该插件，自动创建 `-installed` 标记、软链接与语法校验，直接生效。

### 场景 3：本地一键打包与测试
本工程支持在宿主或本地容器直接打包：
- 打包轻量版（外置底包模式）：
  ```bash
  bash scripts/build-module.sh
  ```
- 本地完整版合成（需本地有基准底包）：
  ```bash
  bash tools/dynamic-rootfs-merge.sh /path/to/base_rootfs.tar.gz rootfs-overlay magisk-module/rootfs.tar.gz
  bash scripts/build-module.sh --full
  ```

---

## 四、 云端 CI/CD 自动化流水线（打包与上传逻辑）

每次推送到 `dsh-magisk` 分支，`.github/workflows/magisk-module-build.yml` 会自动执行：

1. **构建与产物上传（Artifacts）**：
   - 生成轻量版刷机包：`dsha_ksu_native_lite` (~27KB)；
   - 生成全新纯净全内置刷机包：`dsha_ksu_native_full` (~218MB)；
2. **底包 Release 自动挂载**：
   - 自动将纯净底包 `rootfs.tar.gz` 发布/覆盖更新到 GitHub Release [Tag: `0.1.5rc.2-base`](https://github.com/2108282/DSHA/releases/tag/0.1.5rc.2-base)；
   - 提供永久直链：`https://github.com/2108282/DSHA/releases/download/0.1.5rc.2-base/rootfs.tar.gz`；
3. **底包持久化同步到「0.1.5rc.2底包」分支**：
   - 自动按 50MB 分卷切片（`part-00` ~ `part-04`，适配 GitHub 100MB 单文件限制）；
   - 生成合并脚本 `merge.sh` 与说明文档；
   - 自动提交推送到 [0.1.5rc.2底包 分支](https://github.com/2108282/DSHA/tree/0.1.5rc.2底包)，并在其首页更新直链下载索引。

---

## 五、 三大分支职责与关系一览

| 分支名 | 职责定位 | 主要产物与形态 |
| :--- | :--- | :--- |
| **`magisk-apk`** | Android 前端外壳 App (纯 Java/Android SDK) | `dsha-for-root-1.0.apk` (提供 3095 硬件桥与 Web 容器) |
| **`dsh-magisk`** | 原生 Root 模块与运行时管理 (纯 Shell + 动态补丁) | `dsha_ksu_native_full.zip` (开箱即刷模块) |
| **`0.1.5rc.2底包`** | 纯净底包资产持久化仓库 | 50MB 底包分卷实体、合并脚本与 Release 直链 |

*以上规范由 DSHA 架构演进确立，后续所有迭代请严格遵循此模型。*
