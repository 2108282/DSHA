# DSH 核心升级至 0.1.2-rc.1 与双轨鉴权冲突排查修复记录

本文档记录了在 DSHA 容器环境中将核心引擎 `@deepseek-ai/dsh` 从 `0.1.1-rc.2` 升级至 `0.1.2-rc.1` 的完整背景、底层代码改动分析、由此引发的 Token 鉴权冲突根因排查以及最终的双轨闭环修复方案。

---

## 一、 背景与升级诉求

当前 DSHA 运行于 Android 手机的 Linux 容器环境（Proot/Proroot）中。底层核心调度与 WebUI 基于 DeepSeek 官方开源的 `deepseek-harness`（简称 dsh）。

官方近期发布了 `0.1.2-rc.1` 版本，包含会话写入租约锁（Write Lease）、单会话独立投影缓存（Per-Session Projection Cache）以及前端 Client 架构的解耦重构。用户希望在保持容器及插件稳定的前提下升级至最新版本。

---

## 二、 升级前的底层风险评估与清理

在升级前，对容器现有环境和已安装插件进行了源码级排查：

1. **前端大重构风险**：
   - 官方在 `0.1.2-rc.1` 中彻底废弃并移除了 `@deepseek-ai/dsh-client-runtime` 包，将状态管理抽离为 `@deepseek-ai/dsh-client-store`。
   - 排查发现用户安装的第三方插件 `dsh-archived-sessions`（归档会话插件）强依赖了该已废弃包中的 `createSnapshotStore`，升级后会导致前端模块解析失败抛出异常。
   - **处理措施**：在执行升级前卸载了 `dsh-archived-sessions` 插件，确保其余插件（`dsh-agy`、`@xmanrui/dsh-im`、`dsh-session-manager` 及 DSHA 内置插件）平稳过渡。

2. **DSHA 专属环境补丁重跑**：
   - 全局安装完成后，依次重新执行了 Proot 文件写入原子化补丁（`fs-write-patch.sh`）、Web 鉴权补丁（`webserver-auth-patch.sh`）、浏览器回环端口省略修复（`webui-origin-port-patch.sh`）以及 Android WebView Polyfill。

---

## 三、 升级后出现的故障现象

在升级到 `0.1.2-rc.1` 并重启后，出现了以下异常：
- 控制台与启动日志（`~/dsh-web.log`）中输出了全新的随机令牌访问地址：
  `http://127.0.0.1:3080/?token=<新生成的随机LaunchToken>`
- DSHA 客户端 App 的内置 WebView 加载时仍然使用固定的旧令牌地址（`http://127.0.0.1:3080/?dsha_t=<固定BridgeToken>`）。
- 用户通过 App 无法进入 Web 界面，访问被拒绝，返回 401 或 403 错误，页面无法加载。

---

## 四、 根因深度排查

经过对官方提交历史、架构文档以及 DSHA 源码的比对，确认该故障由两套机制的交织冲突引起：

### 1. 官方新增的进程启动令牌机制（Launch Token）
官方在 `0.1.2` 版本中增强了安全性，引入了基于 HMAC 签名的浏览器会话鉴权（位于 `packages/client/connection/src/browser-auth.ts` 与 `dsh-client-connection`）：
- 每次 Node.js 启动时生成一个随机的进程启动令牌（`launchToken`）。
- 首次访问必须通过 `GET /?token=<launchToken>` 进入，由服务端验证通过后使用 `303 See Other` 下发经过签名的 HTTP-Only Cookie（名称形如 `dsh-auth-<hash>`），随后清除 URL 中的参数重定向到 `/`。
- 如果请求没有合法的官方 Cookie 且 URL 中没有正确的 `token` 参数，`authorizeIndex` 统一返回 `401 Unauthorized`。

### 2. DSHA 原有的客户端桥接令牌机制（Bridge Token）
DSHA 为防止本地其他未授权应用访问 `127.0.0.1:3080`，设计了一套基于文件令牌的鉴权方案：
- 令牌固化保存在 `/root/.dsh/.bridge_token`。
- DSHA 客户端 Android 源码（`LaunchFragment.java`）在拼接访问 URL 时固定追加 `?dsha_t=<bridge_token>`。
- DSHA 在 `dsh-host-webserver` 入口注入了 `__dshaAuthOk` 中间件，只校验 URL 参数 `dsha_t` 或 Cookie 中的 `dsha_t=<token>`。

### 3. 冲突焦点与双重拦截
- **第一层冲突**：客户端带着 `?dsha_t=...` 访问时，通过了 DSHA 的 `webserver-auth`，但撞上了官方新增的 `authorizeIndex`。官方代码只认 `TOKEN_QUERY`（即 `token`），对 `dsha_t` 视而不见，直接判定未授权返回 401。
- **第二层冲突（重定向后拦截）**：在初次为 `authorizeIndex` 增加 `dsha_t` 兼容后，官方代码成功验证并下发了官方的 `dsh-auth-xxx` Cookie，并将客户端重定向至根路径 `/`。但重定向后的请求不再携带 `?dsha_t` 参数，DSHA 自身的 `__dshaAuthOk` 中间件不识别官方的 `dsh-auth-` Cookie，反而误将持有官方凭证的合法请求拦截并返回 403 Forbidden。

---

## 五、 完整修复方案（双轨闭环兼容）

为了在不修改 Android 原生 App 编译包的前提下实现无缝升级，在容器侧对 Node.js 运行层进行了双向打通：

### 1. 官方连接层：允许 `dsha_t` 换取官方 Cookie 并双重下发
修改 `/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-connection/lib/index.js`：
- 在文件头部引入 `readFileSync`：
  ```javascript
  import { readFileSync } from "node:fs";
  ```
- 在 `authorizeIndex(req, res)` 入口处增加 DSHA 桥接令牌识别逻辑。当检测到 `?dsha_t=` 且值与 `/root/.dsh/.bridge_token` 一致时，同样签发官方 HMAC Cookie，并在重定向响应中同时写入官方 Cookie 与 DSHA 的 `dsha_t` Cookie：
  ```javascript
  const dshaT = url.searchParams.get("dsha_t");
  if (dshaT) {
      try {
          const expectedToken = readFileSync("/root/.dsh/.bridge_token", "utf8").trim();
          if (expectedToken && dshaT === expectedToken) {
              const authority = requestAuthority(req.headers);
              if (req.method === "GET" && url.pathname === "/" && authority !== void 0) {
                  const issuedAt = Date.now();
                  const expiresAt = issuedAt + this.maxAgeMilliseconds;
                  const value = encodeCookie({
                      version: COOKIE_PAYLOAD_VERSION,
                      authority,
                      issuedAt,
                      expiresAt
                  }, this.secret);
                  res.writeHead(303, {
                      "cache-control": "no-store",
                      "location": "/",
                      "referrer-policy": "no-referrer",
                      "set-cookie": [
                          sessionCookie(cookieName(authority), value, expiresAt, Math.floor(this.maxAgeMilliseconds / 1e3)),
                          "dsha_t=" + expectedToken + "; Path=/; SameSite=Strict; Max-Age=31536000"
                      ]
                  });
                  res.end();
                  return false;
              }
          }
      } catch (e) {}
  }
  ```

### 2. Web 守卫层：识别并放行官方认证 Cookie
修改 `/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-host-webserver/lib/index.js`：
- 在 `__dshaAuthOk` 中增加对官方 Cookie 前缀（`dsh-auth-`）的识别放行：
  ```javascript
  const cookie = req.headers?.cookie || "";
  for (const part of cookie.split(";")) {
      const p = part.trim();
      if (p === "dsha_t=" + tok || p.startsWith("dsh-auth-")) return true;
  }
  ```

---

## 六、 验证与结果

1. **语法校验**：经 `node --check` 验证，修改后的 ESM 脚本语法正确，无依赖缺失。
2. **端到端 HTTP 验证**：
   - 发送 `GET /?dsha_t=<bridge_token>` 请求，服务端返回 `303 See Other`，并在响应头中正确携带官方签名 Cookie 与 `dsha_t` Cookie。
   - 跟随重定向携带该 Cookie 访问根路径 `/`，页面正常返回 200 状态码并完成前端静态资源加载。
3. **自检结果**：运行 `selftest.py`，全部 25 项关键健康检查无一失败，系统与插件运行正常。
