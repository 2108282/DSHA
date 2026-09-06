# Termux JNI 16KB 重编

2026-09-05 后续修复：仍为 API 23，max-page-size=16384、common-page-size=4096，
避免 RELRO 延伸到未映射的 4 KB 空洞导致旧 linker 报 ENOMEM。现行 SHA-256：
`52bb4a06db4a2805fc35f7d33892a78563dce5d64f1f558d8713330cc0aefe15`。
Android 13 真机终端及 npm 安装通过；旧哈希均为历史记录。

兼容版现使用 `-MinApi 23` 重编同一份 C 源码（同时适用标准版），输出 SHA-256 为
`bc3de9dc57d0e886866b95ef077619317704ebc7261c674d14824349da8fcdc8`。
下方旧 API 26 哈希保留为历史记录；复现兼容版请加 `-MinApi 23`。

## 来源

- [Termux v0.118.0 原版 termux.c](https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/terminal-emulator/src/main/jni/termux.c)，提交 `6e2689f55295fa444be8ac8592c527c2c5ef3253`；源码无功能改动。
- 保留 `LICENSE.upstream.md`（含 terminal-emulator 的 Apache 2.0 例外）及 `LICENSE-2.0.txt` 全文。

## 重编命令

在仓库根目录的 PowerShell 执行：

```powershell
& ./tools/termux-jni/build.ps1 -Ndk 'F:/DSHA/_toolchains/android-sdk/ndk/26.3.11579264'
```

使用已有 NDK r26d / clang 17.0.2，目标 `aarch64-linux-android26`。
真实链接参数包含 `-Wl,-z,max-page-size=16384` 和 `-Wl,-z,common-page-size=16384`。
脚本不下载工具、不调用 Gradle，校验后输出至 `app/src/main/jniLibs/arm64-v8a/libtermux.so`。

## 验证摘要

- ELF64 / AArch64，SONAME `libtermux.so`；三个 LOAD 段均为 `0x4000`，16KB 地址同余检查通过。
- 与原 0.118.0 AAR 的 5 个 JNI 导出完全一致；由 `javap` 核对现有 Java 的 4 个 native 方法签名，加载名仍为 `termux`。
- 动态依赖仅系统 `libc.so`、`libdl.so`。两次独立链接得到相同文件，大小 **9576 字节**。
- SHA-256：`411e90ce4cbd1defd7ee19cb5fd62b430c055de2c7ef447a1d846da26ec56f02`。
- GNU Build ID：`c5b7315b045907ebbce37ad12a414332d1779ffe`。

本任务未修改 Gradle、Java、Manifest，未做备份、全量测试或设备验证。
父任务负责 `pickFirsts` 去重与最终 APK 核对；若后续 strip 改变哈希，结合 Build ID、LOAD 对齐和 JNI 导出确认选中重编库。
