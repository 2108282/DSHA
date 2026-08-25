# 更新记录

> 只记面向用户的变化。完整历史见 [commit log](https://github.com/qiannianhuanxiang/DSHA/commits/main)。

### 📺 v1.1.8：AI 输出实时上屏，危险命令就地批准

屏幕顶部多了一条**流式悬浮条**：agent 正在生成的内容像歌词一样实时滚出来，
调工具时显示成「⚙ 正在执行命令: ls -la」——带命令原文，而不只是「正在执行命令」。
底色、不透明度、显示行数、停留时间都能调，带预览按钮，不用等 agent 说话就能试样式。
思考过程（reasoning）可选显示。**默认关闭**：内容会直接显示在屏幕上，旁边的人也看得见。

危险命令的确认也搬到了悬浮条上 —— 原来只有通知和前台弹窗两条渠道，可 agent 干活时
用户往往并不在 App 里。现在是**第三条渠道**而非替代：三条共用同一个 epoch，谁先点谁生效。

免 ROOT 做不到真正的「状态栏歌词」（只有 Flyme/exTHmUI 认 ticker flag，其余机型要
Xposed hook 系统界面），所以走自绘悬浮窗：一次性授权、全 ROM 通用。

**内置移动端适配换成 [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)**（MIT）：
窄屏单栏 + 目录抽屉、设置改底部 sheet、状态栏安全区、表格与气泡排版。
旧插件作者长期停更；升级时会自动把它从 profile 摘掉并删除实体（两个插件改造同一批
DOM 元素，同时激活会互相打架），此前手动禁用过的话新插件也保持禁用。

同版修掉的问题里，有几个是「一直没人发现它是坏的」那类：

| 问题 | 根因 |
|---|---|
| 局域网访问打不开 | 剥离 token 时把请求行的 HTTP 版本一起吃掉（`GET /?token=x HTTP/1.1` → `GET /`），后端直接 400 |
| 局域网页面能开但一直转圈 | token 靠浏览器自动带的 `Referer` 生效，而 WebSocket 握手不发 Referer → 必然 401；顺带 token 会随外链泄漏给第三方 |
| 装过 rc 版就再也收不到更新 | 版本号解析把 `7-rc81` 拼成 `781`，比任何正式版都「新」 |
| 脚本热更新永久失效 | 增量清单落后于 assets：下到新文件、校验旧哈希、整批丢弃，界面上什么都不说 |
| 自检脚本一启动就崩 | 正则里 `\(` 写成 `\\(`，模块级 `re.compile` 直接抛错 |

现在这些都有守门人盯着：新增 Fast checks 流水线（清单一致性 + 离线验签 + 纯逻辑断言 +
assets 脚本真编译），发布时证书指纹不匹配直接中止 —— 发一个用户装不上的包比发布失败糟。

### 🔐 v1.1.7：对话数据不再随卸载消失

会话、设置、附件迁到 **内部存储/Documents/dshdata**，原位留私有符号链接。
文件管理器里直接可见、可自行备份，**卸载 App 或换机重装后数据仍在**。

进入 App 会自动申请「所有文件访问」权限并说明用途；授权后立刻迁移，
不必等下次启动。自检新增「对话数据存放位置」一项，明确告诉你现在到底会不会丢 ——
之前这个迁移在缺权限时会**静默跳过**，用户以为安全了其实没有。

刻意留在私有目录的东西：`DSH_HOME` 本体（dsh 维护的 `node_modules` 符号链接，
公开 FUSE 禁止软链）、`.credentials.yaml`（公开区强制 660，且密钥会暴露给其他 App）。
**API Key 改用 Android Keystore 加密**（AES/CBC，密钥不出 Keystore），
备份里的那份也加密后再写 —— 此前是明文进 `Download/DSHA` 公共目录。

同版还修了两个影响日常使用的问题：

| 问题 | 根因 |
|---|---|
| 每条 agent 命令都弹危险确认 | 守卫把我们自己注入的 `source …dsh-guard.sh 2>/dev/null;` 前缀当成用户命令，其中的 `>` 让「覆盖关键路径」判据恒真 |
| 深色模式按钮浅蓝底浅字看不清 | Material3 把 `<Button>` 膨胀成 MaterialButton 并用 `colorPrimary` 填充，**忽略 `android:background`**，对比度只有约 1.3:1 |

### 🚀 v1.1.6 起：默认 proroot 运行时，启动快 5~6 倍

传统 proot 基于 ptrace，**每个系统调用要两次上下文切换**；
[proroot](https://github.com/coderredlab/proroot) 改用 LD_PRELOAD + 二进制补丁做
进程内路径翻译，零 ptrace 开销。装完即生效，想用回 proot 可在「配置」页关掉。
真机实测（vivo V2352A / Android 14）关键项合计 **+58%**，
其中 tar 打包 +94%（备份走这条）、stat 密集 +82%（node 模块解析）。

**兜底机制**：运行时文件缺失自动降回 proot；连续 3 次启动失败强制切回并告知；
装机路径始终用 proot。最坏情况只是回到原来的速度，不会让环境不可用 ——
这也是敢把闭源组件设为默认的前提。见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

