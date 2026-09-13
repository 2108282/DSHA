# DSHA 1.2.0-rc1.3-update2 核心修复与底包换装技术总结文档

本文档记录了基于 `test` 分支排查定位并彻底修复的全部核心功能缺陷、系统底层根因、以及将离线底包独立换装固化为 `@deepseek-ai/dsh@0.1.5-rc.2` 的完整技术细节。

---

## 目录
1. 抽屉文件上传失败（EACCES / 403 / 卡住）底层修复
2. 抽屉选文件独立性修复（彻底根治跳转容器内部问题）
3. 抽屉外部链接拦截调起系统默认浏览器
4. 抽屉右上角「+」号（新建会话）双轨路由自愈
5. 容器启动报错 `ReferenceError: link is not defined` 根治
6. 离线底包脱钩换装为锁定的 `dsh 0.1.5-rc.2`（出厂即固化，覆盖自动感知）
7. 智能体多步任务与 UI 进度卡片实时同步强执行纪律（`AGENTS.md`）
8. 修改与新增文件清单全览
9. 1.2.0-rc1.3-update2 增量综合修复升级说明
10. 1.2.0-rc1.3-update2.1 全景全透与视觉修复升级说明

---

## 1. 抽屉文件上传失败（EACCES / 403 / 卡住）底层修复

### 问题现象
用户在抽屉快捷对话中，上传图片可以作为草稿正常展示，但只要上传任意普通文件（如 `.tar.gz` 压缩包、文本、代码等），文件卡片立即变红并显示 **“上传失败，点击重试”**。

### 根本原因
1. **Cookie 鉴权缺失（403 拦截）**：
   - 图片格式在前端直接走主线程 `FileReader.readAsDataURL` 编码为 Base64 草稿，不走后端二进制存储；
   - 普通文件一被选中，前端后台 Web Worker 立即发起 `POST /api/session/uploadFileBinary?sessionId=...&name=...`；
   - 该请求不带 `token=` 参数，100% 依赖浏览器 Cookie；抽屉原先未在底层向 `CookieManager` 预埋 `dsh-auth-*` 与 `dsha_t` 凭证，直接被服务端打回 **HTTP 403 Forbidden**。
2. **只读文件覆写冲突（EACCES: permission denied）**：
   - 真实抓包报错：
     ```text
     ATTACHMENT_FAIL_TRACE: Error: EACCES: permission denied, copyfile
     '/root/.dsh/attachments/v1/file-objects/...' -> '/root/.dsh/attachments/v1/files/.../DSHA-backup-auto-2.tar.gz'
       errno: -13, code: 'EACCES', syscall: 'copyfile'
     ```
   - 文件首次写入后被设为 `0o400` 只读模式；当用户第二次上传同名文件、或者点击重试时，补丁脚本 `fs-write-patch.sh` 中的 `copyFile(source, target)` 试图强行覆写该只读文件，触发 Linux 内核的 `EACCES` 报错并抛出 `ATTACHMENT_WRITE_FAILED`。

### 修复实现
1. **`QuickChatSheetActivity.java`**：在 WebView 初始化时后台线程向 `CookieManager` 预埋持久通行的 `dsha_t=<token>` 与 `dsh-auth-*` Cookie，并执行 `cookies.flush()`，彻底解决 403 拦截。
2. **`app/src/main/assets/fs-write-patch.sh`**：
   在执行 `copyFile` 之前预检目标文件是否已存在，若已存在且哈希完整则直接作为发布成功放行，绝不重复覆写只读文件；并在异常处理中全面兼容 `EACCES / EEXIST / EPERM`。

---

## 2. 抽屉选文件独立性修复（彻底根治跳转容器内部问题）

### 问题现象
在抽屉中点击附件按钮，手机界面突然发生跳变，处于后台的应用主界面（`MainActivity` 容器内部）被拉到最前台。

### 根本原因
1. 之前版本引入了单独的中转 Activity 处理文件选择，由于该组件未设置独立任务栈亲和性，系统在启动它时顺带把后台的主任务栈（`MainActivity`）一起激活拉到了手机前台；
2. 而如果不用中转，抽屉原先声明为 `launchMode="singleInstance"`，调用 `startActivityForResult` 调起系统文件选择器时，系统底层为了保护 singleInstance 栈的单一性，会**在拉起选择器的同一毫秒内提前派发 `RESULT_CANCELED`**，导致选中的文件在返回前回调已被清空。

### 修复实现
1. **彻底移除所有多余的中转 Activity**；
2. 在 `AndroidManifest.xml` 中将 `QuickChatSheetActivity` 的 `launchMode` 调整为 **`singleTask`**，同时保留专属独立任务栈 `android:taskAffinity="com.dsh.client.quick_sheet"`；
3. `QuickChatSheetActivity` 直接继承 **`androidx.activity.ComponentActivity`**，原生内置官方标准的 `registerForActivityResult`；
4. 选文件完全在抽屉弹层之上呼出系统文件管理器，选完立即回到抽屉，**自始至终绝不触碰容器主界面，100% 原生独立**。

---

## 3. 抽屉外部链接拦截调起系统默认浏览器

### 问题现象
在抽屉中点击网页里的外部链接（如 GitHub、文档、网址）时，WebView 直接在抽屉内部强制跳转打开该外网网页，冲刷掉当前会话界面。

### 修复实现
在 `QuickChatSheetActivity.java` 的 `WebViewClient` 中重写 `shouldOverrideUrlLoading`：
- 对 `http://127.0.0.1:3080` 与 `http://localhost:3080` 的内部请求绝对放行；
- 对所有非本地的外部 `http/https` 链接一律拦截（返回 `true`），并以 `FLAG_ACTIVITY_NEW_TASK` 安全调起手机系统默认浏览器（Chrome/Via等）外部打开；
- 抽屉自身永远锁定在 DSHA 会话界面。

---

## 4. 抽屉右上角「+」号（新建会话）双轨路由自愈

### 问题现象
在抽屉里如果不慎离开了主页，再点击右上角 `+` 号无法新建会话，页面只在当前错误页面死循环刷新。

### 修复实现
在 `QuickChatSheetActivity.java` 的 `btnNewChat` 点击监听器中实现双轨防御：
1. **原生层自愈**：如果检测到当前 URL 脱离了本地 3080 服务，Java 侧直接强制加载官方原生地址 `controller.getWebAuthUrl()`；
2. **DOM 与路由自愈**：在本地服务内优先通过 DOM 寻找新建会话按钮模拟点击，按钮不可见时强制将路由导向根路径 `/`（`window.location.href = '/'`），任何状态下点击均能秒开新对话。

---

## 5. 容器启动报错 `ReferenceError: link is not defined` 根治

### 问题现象
覆盖安装新包后，容器启动闪退并报错：
```text
ReferenceError: link is not defined
    at dsh-session-persistence-jsonl/lib/index.js:1608:2
[proroot] child exited with code 1
```

### 根本原因
1. 老版 Java 补丁逻辑 `ProotBootstrap.patchLinkToRename` 在处理导入时，盲目将 `import { link, ... }` 替换成了 `import { rename, ... }`，直接将 `link` 从导入列表中抹掉了；
2. 而新版 dsh 0.1.5 的 `defaultFileSystem` 对象（第 1630 行）仍然显式引用了 `link` 函数；
3. `fs-write-patch.sh` 在检测时发现已有 `rename` 就误判为已就绪跳过，导致缺失 `link` 的致命语法错误无法自愈。

### 修复实现
1. **`ProotBootstrap.java`**：精准解析 `node:fs/promises` 导入块，严格保证 `rename` 与 `link` 两者同时存在于 import 中；
2. **`fs-write-patch.sh`**：移除草率跳过的判断，严格核验 `rename` 和 `link` 导入完整性，遇到缺漏自动修补。

---

## 6. 离线底包脱钩换装为锁定的 `dsh 0.1.5-rc.2`

### 改造背景
原 CI 工作流（`.github/workflows/android-build.yml`）硬编码从外部第三方地址下载旧版 APK 提取底包，既存在外部链接随时失效断供的风险，又导致安装包内固定停留在老版本 `0.1.2`。

### 换装与脱钩实现
1. **底包换装**：利用工程自带的 `tools/rebuild-dsh.py`，在 Ubuntu 纯净底包内将旧版 `dsh` 精准剔除，无缝换装注入锁定的 `@deepseek-ai/dsh@0.1.5-rc.2`，生成 193 MB 的新底包；
2. **完全自持资产**：将做好的成品底包发布在你自己的 GitHub Release（`0.1.5rc.2-base`）下；
3. **CI 极速秒级拉取**：`.github/workflows/android-build.yml` 改造为直接从自身 Release 秒级拉取 `offline-rootfs.bin`，打包时间缩短近 2 分钟，100% 摆脱第三方外部依赖；
4. **覆盖安装自动感知**：`Constants.java` 中 `DSH_VERSION` 设为 `0.1.5-rc.2`，`offline-rootfs.version` 设为 `11`，覆盖安装时 App 会自动识别版本差异并触发环境更新。

---

## 7. 智能体任务执行纪律（`AGENTS.md`）

在工作区与仓库根目录部署 `AGENTS.md`，规范智能体在执行多步骤任务时，必须在每一步完成之后立即调用 `todo_write` 更新状态，确保前端 UI 任务进度卡片实时同步打勾推进。

---

## 8. 修改与新增文件清单全览

| 文件路径 | 改动属性 | 核心改动点说明 |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | 修改 | 抽屉改为 singleTask 独立任务栈；声明 largeHeap 杜绝 OOM |
| `app/src/main/res/xml/update_file_paths.xml` | 修改 | 增加 `web_uploads` 的 FileProvider 路径映射 |
| `app/src/main/res/values/themes.xml` | 修改 | 保持纯净全透明抽屉主题，移除多余样式 |
| `app/src/main/res/values-night/themes.xml` | 修改 | 深色模式全透明抽屉主题配置 |
| `app/src/main/assets/fs-write-patch.sh` | 修改 | 修复只读文件覆写 EACCES 报错；修复 link 导入缺失 |
| `app/src/main/java/com/deepseekharness/app/util/WebTransferPolicy.java` | 新增 | 文件名安全清洗、单次 20 个 / 256MB 上限控制 |
| `app/src/main/java/com/deepseekharness/app/ui/WebUploads.java` | 新增 | 异步转储到私有缓存，封装生成 FileProvider 安全 URI |
| `app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java` | 修改 | 恢复 AllowContentAccess，接入后台转储与 FileProvider |
| `app/src/main/java/com/deepseekharness/app/ui/QuickChatSheetActivity.java` | 修改 | 继承 ComponentActivity 原生选文件；Cookie 强同步；外链调系统浏览器；+号双轨路由自愈 |
| `app/src/main/java/com/deepseekharness/app/runtime/ProotBootstrap.java` | 修改 | 修复 patchLinkToRename 误删 link 导入；终端保持原始基线 |
| `app/src/main/java/com/deepseekharness/app/util/Constants.java` | 修改 | DSH_VERSION 锁定为 0.1.5-rc.2 |
| `app/src/main/assets/offline-rootfs.version` | 新增 | 版本标记为 11，支持覆盖安装自动识别 |
| `.github/workflows/android-build.yml` | 修改 | 直接从自身 Release 拉取 0.1.5-rc.2 现成底包，脱离外部依赖 |
| `app/build.gradle` | 修改 | 版本升级为 1.2.0-rc1.3-update2，versionCode 114 |
| `.github/workflows/release.yml` | 修改 | 对齐自身 Release 0.1.5rc1-base 底包资产下载与 release 发布说明 |
| `AGENTS.md` | 修改 | 写入任务进度实时同步强执行纪律规范 |

---

## 9. 1.2.0-rc1.3-update2 增量综合修复升级说明

1. **QuickChat 快捷对话抽屉全景沉浸全透明**：
   - 消除顶部状态栏空白条，全屏顶格沉浸；
   - 继承 `AppCompatActivity` 完美联动日夜间模式，日间视口彻底透明透光、黑夜代码块与输入框半透明微光；
   - 输入框底座全透明，采用视口二段物理截断（`margin-bottom` 联动），长对话滚动至输入框上方自然消失，彻底杜绝穿透与重叠；
   - 设置页新增【快捷抽屉沉浸全透明】独立开关，输入框底座自适应微透光。
2. **pnpm 包管理器全局部署与离线自愈**：
   - 安装与修复时同步将执行入口部署到 `/usr/local/bin/pnpm` 系统全局路径，彻底根治插件管理器找不到 pnpm 报错。
3. **局域网访问与 Token 永久固定**：
   - 局域网访问 Token 永久持久化，重启不重置，启动页增加重新生成按钮。
4. **Web 预览与下载能力增强**：
   - 支持网页文件及 Blob 链接原生下载与安全校验，恢复 `resumeTimers` 杜绝卡白屏。
5. **插件架构与自愈强化**：
   - 规范第三方插件真实目录，自动建立 `@deepseek-ai` 软链自愈，植入 3 秒极速启动缓存。
6. **备份恢复全量覆盖**：
   - 图片与文件附件库（attachments）正式纳入全量备份，修复对话索引与工作区丢失。
7. **功耗与心跳彻底优化**：
   - 熄屏无任务自动释放 WakeLock 与 Wi-Fi 锁，深度休眠不发热不偷跑电，后台有长任务时自动稳固持锁。

---

## 10. 1.2.0-rc1.3-update2.1 全景全透与视觉修复升级说明

1. **上层文件列表与文档预览恢复 100% 全景全透明**：
   - 移除此前误加的实心背景，显式声明全量文件抽屉与文档预览容器全透明，通透透出手机壁纸。
2. **全景下层遮挡自动隐身机制**：
   - 当右侧文件抽屉、文档预览、左侧抽屉或设置弹窗等展开时，底层主会话流与输入框自动进入 `visibility: hidden !important; opacity: 0 !important;` 状态，彻底根治上下图层文字穿透与字体重叠乱码。
3. **抽屉深浅色模式与容器 App 状态动态同步**：
   - `ThemeController.select` 动态分发给 WebView，同步 `setForceDark` 内核状态与 Web 标准 `colorScheme` 属性，彻底解决 `dsh-api-dashboard` 等插件在抽屉中被误识别为白天浅色模式的问题。
4. **移动端设置面板居中与全宽自适应修复**：
   - 剔除侧边栏抽屉的 `backdrop-filter`，消除对全局 fixed 模态框的包含块（containing block）束缚，根治设置面板向左偏移截断的严重视觉缺陷。
