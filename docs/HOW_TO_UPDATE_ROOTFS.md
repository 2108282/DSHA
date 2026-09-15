# DSH 官方更新与底包迭代维护指南

本文档记录当 DSH 官方发布新版本，或我们需要调整底包依赖时，如何重新生成全新底包与 KernelSU / Magisk 刷机包。

---

## 一、 底包的核心构成

底包（`rootfs.tar.gz` / `rootfs.tar.xz`）本质是一个纯净的 **Ubuntu ARM64 (glibc)** 运行环境，关键组件路径如下：

- **Node.js 运行时**: `/usr/local/bin/node` (v24.19.0)
- **包管理器**: `/usr/local/bin/pnpm` (v10.34.5)
- **DSH 核心源码与运行时**: `/usr/local/lib/node_modules/@deepseek-ai/dsh/`
- **DSH 全局命令软链**: `/usr/local/bin/dsh -> ../lib/node_modules/@deepseek-ai/dsh/lib/bin.js`
- **3090 硬件桥 Token**: `/root/.dsh/.bridge_token`

---

## 二、 DSH 官方更新后的底包制作流程（三步完成）

### 步骤 1：进入原生 chroot 终端
在手机终端（MT 管理器或 Termux Root）中执行：
```bash
su -c "/data/adb/dsha/scripts/term.sh"
```
成功进入后，命令提示符为 `root@localhost:/#`。

### 步骤 2：升级 DSH 核心包
根据官方发布渠道，选择以下升级命令之一：

- **方式 A（npm / pnpm 在线升级至最新版）**：
  ```bash
  # 升级到官方最新版本
  pnpm install -g @deepseek-ai/dsh@latest
  # 或者升级到指定版本号（如 0.1.6）
  # pnpm install -g @deepseek-ai/dsh@0.1.6
  ```

- **方式 B（从 GitHub 源码更新并构建）**：
  ```bash
  cd /usr/local/lib/node_modules/@deepseek-ai/dsh
  git pull
  pnpm install
  pnpm build
  ```

升级完成后，验证版本号：
```bash
dsh --version
```
验证无误后，输入 `exit` 退出容器终端，回到 Android 宿主。

### 步骤 3：一键脱敏打包并生成新模块
在 Android 宿主终端执行维护脚本：
```bash
/sdcard/Download/DSHA/dsha-ksu-project/tools/export-rootfs.sh
```
**脚本执行内容**：
1. 自动清理 `apt` 缓存、`/tmp` 临时文件与 npm 缓存；
2. **严格脱敏**：自动剔除你的私有聊天记录（`sessions/`）、对话附件、API Key 账号信息与历史指令；
3. 多线程并行压缩生成全新的通用底包 `rootfs.tar.gz` 与 `rootfs.tar.xz`；
4. 自动重新打包生成最新的 `dsha_ksu_native_v1.2.0.zip`。

产物全部就绪于：
`/sdcard/Download/DSHA/dsha-ksu-project/release/`

---

## 三、 发布与同步

- 将 `rootfs.tar.gz` 上传至你 GitHub 仓库的 Release 资产中；
- 将 `dsha_ksu_native_v1.2.0.zip` 分享给用户一键在 KernelSU / Magisk 中刷入更新；
- 已安装用户也可以直接通过覆盖刷入模块实现无痛平滑升级。
