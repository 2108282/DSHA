# DSHA 图片上传与附件持久化修复记录 (ATTACHMENT_WRITE_FAILED)

本文档记录了在 **DSHA (DeepSeek Harness on Android)** 环境下，Web 端发送图片出现 `(ATTACHMENT_WRITE_FAILED)` 错误的根本原因、排查过程、代码修改与长期维护方案。

---

## 目录
1. [故障现象](#一故障现象)
2. [根本原因分析](#二根本原因分析)
3. [修复方案与代码实现](#三修复方案与代码实现)
4. [环境生效与服务重载](#四环境生效与服务重载)
5. [实测验证结果](#五实测验证结果)

---

## 一、故障现象

在 DSHA WebUI（端口 `3080`）的输入框中添加、粘贴或拖拽图片并点击发送时，前端对话框弹出红色告警：
```text
图片发送失败（ATTACHMENT_WRITE_FAILED），请重新添加图片后再试
```
导致多模态模型无法接收到图片，图片附件无法保存到会话对象池中。

---

## 二、根本原因分析

1. **Android 存储系统（FUSE/sdcardfs）限制**：
   - DSHA 运行在 Android PRoot 容器内，为了让外部应用和备份系统访问数据，`/root/.dsh/attachments` 软链接到了宿主机的共享存储目录 `/sdcard/Documents/dshdata/attachments`。
   - Android 的外部存储文件系统（`/sdcard` 虚拟层）**不支持 POSIX 硬链接（`link` 系统调用）**。任何在该目录下执行 `link()` 的调用均会被内核直接拒绝，返回 `EINVAL` (Invalid argument) 或 `EPERM`。

2. **官方附件模块实现冲突**：
   - 官方 `@deepseek-ai/dsh-attachment-local` 在处理图片落盘发布时，位于 `commitPreparedImageFile` 函数中的逻辑为：
     ```javascript
     // 官方原版代码：先写入 staging 临时文件，再通过 link 原子发布到 objects/
     await link(temporary, target);
     await unlink(temporary);
     ```
   - 在 Android `/sdcard` 上执行 `await link(temporary, target)` 必然抛出 `EINVAL`，该异常被捕获后包装成 `ATTACHMENT_WRITE_FAILED` 错误，阻止了图片正常发送。

---

## 三、修复方案与代码实现

### 1. 修改文件
- 目标路径：`/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js`
- 备份路径：`/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js.dsha-bak`

### 2. 补丁逻辑（rename 代替 link）
将 `commitPreparedImageFile` 中的硬链接发布机制改造为跨平台兼容的原子 `rename` 移动发布（并在跨挂载点出现 `EXDEV` 时自动回退至复制+删除），与 DSHA 已改造的 `dsh-fs-local` 及 `dsh-session-persistence-jsonl` 保持架构一致：

```javascript
/* DSHA_L2S_FIX_ATTACHMENT —— 一律 rename 发布。Android/proot 下 link() 报错 EINVAL/EPERM */
try {
    await rename(temporary, target);
} catch (error) {
    if (error && error.code === "EXDEV") {
        const { copyFile } = await import("node:fs/promises");
        await copyFile(temporary, target);
        await unlink(temporary).catch(() => {});
    } else {
        throw error;
    }
}
await syncDirectory(bucket);
await syncDirectory(join(root, "objects"));
```

### 3. 记录维护补丁
已将本次修补记录记入 `/root/.dsh/fs-write-patch.log`，方便版本升级与自愈检查追踪。

---

## 四、环境生效与服务重载

Node.js 在运行时会在内存中缓存已加载的 CJS/ESM 模块。修改代码文件后，必须对运行中的主服务进程进行重载：
1. 终止旧 Node.js 实例（PID 6123）；
2. 看门狗及启动脚本自动拉起新进程（PID 14198+）；
3. 新服务启动时自动加载打了补丁的 `@deepseek-ai/dsh-attachment-local`。

---

## 五、实测验证结果

1. **底层 API 自动化测试**：
   - 构造标准 PNG、带透明通道 PNG、JPEG 以及超大分辨率（4000×3000）图片进行 `admitEncodedImages`、8-bit sRGB 归一化压缩和持久化读写；
   - 全部顺利完成 SHA-256 校验并成功存入 `/root/.dsh/attachments/v1/objects/`。

2. **Web 端全链路真实测试**：
   - 用户在手机端直接发送包含京东 App 界面与 DSHA 守门人弹窗的真实截屏；
   - 图片秒级上传成功，未再出现 `ATTACHMENT_WRITE_FAILED` 报错，多模态模型顺利识别并解析图片内容。
