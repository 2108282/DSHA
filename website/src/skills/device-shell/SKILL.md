---
name: device-shell
description: 在 DSHA 1.2.0-rc1.1 中通过应用管理的 ADB Shell 通道读取 Android 设备信息、执行经用户授权的设备操作，并验证结果。
---

# 手机命令操作

DSHA 1.2.0-rc1.1 适配版，适用于 Ubuntu/proot 环境和 dsh 0.1.2-rc.1。
基于 DSHA 项目的 device-shell 技能整理，MIT 许可。

## 先确认环境

Ubuntu 终端命令操作的是容器；设备命令必须通过 DSHA 管理的设备通道。
请先在 DSHA 中启用 ADB 通道并完成配对或连接。Android 11+ 可以使用系统无线调试配对码；旧系统使用适合该系统且已获授权的连接方式。

使用当前应用推荐的入口进行只读检查：

```sh
/root/dsh-bin/adb-shell "id"
/root/dsh-bin/adb-shell "getprop ro.product.model"
/root/dsh-bin/adb-shell "getprop ro.build.version.release"
```

正常设备 Shell 通常显示 uid=2000(shell)。核对设备型号，避免对错误设备执行操作。
不要用裸 adb 或 /root/dsh-bin/adb 代替这个入口。
包装入口确实缺失时，可以使用应用提供的备用脚本：

```sh
python3 /root/.dsh/adb-shell.py "id"
```

连接未就绪时，向用户说明 App 中的通道状态和需要完成的设置。不要循环重复连接或重新配对。

## 操作方法

1. 明确用户目标；先读取现状，再决定是否需要修改。
2. 修改设置、点击、输入、安装应用等操作前，说明具体对象和预期影响，保留应用既有的确认流程。
3. 将单个设备命令作为 adb-shell 的参数传入；不要把用户提供的文字直接拼成可执行 shell 语法。
4. 执行后检查实际输出和退出状态，再以只读命令或截图验证结果。
5. 如果出现 USER_REJECTED、用户取消或确认超时，停止该操作，不更换通道重试。

只读例子：

```sh
/root/dsh-bin/adb-shell "dumpsys battery"
/root/dsh-bin/adb-shell "df -h /data"
```

## 能力边界

- 不把 root 当成默认能力，也不绕过 Android 或 DSHA 的授权。
- 不设置关闭确认或绕过守卫的环境变量。
- ADB、Shizuku 与无障碍是不同通道；某一通道失败不代表另一通道已获授权。
- 本机桥 token 仅用于本机调用，不发送给网站或外部模型，不写进分享内容。
- 不自动完成付款、密码修改或对外提交；这些动作交由用户确认或接手。

## 技能位置

将本文件保存在 /root/.agents/skills/device-shell/SKILL.md，或项目根目录的 .agents/skills/device-shell/SKILL.md。
使用包含 skill-filesystem/tool-skill 的 Agent preset（例如 standard）。目录通常自动更新；发现失败时先检查目录层级、frontmatter 和 preset，再新建会话验证。
