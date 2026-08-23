# DSHA Android 客户端 Bug 报告

> 提交日期：2026-08-23
> App：DSHA (com.dsh.client) **v1.1.4** (versionCode=25, targetSdk=34, minSdk=26)
> 设备：vivo 手机（Android 16, SDK 36, user 版, ro.debuggable=0）
> 提交人：DSHA 用户

---

## 概述

发现 **2 个相互关联的严重 Bug**，均位于 App 内嵌的 3090 确认桥（HTTP 桥服务）：

1. **Bug A（阻断性）**：3090 桥监听在 IPv6 回环地址 `::1`，而 App 注入容器内的客户端脚本（`adb-shell.py` / `dsh-confirm.sh`）写死连接 IPv4 的 `127.0.0.1` → **确认弹窗永远无法发出**，所有非只读设备命令被静默拒绝（表现为 `USER_REJECTED`）。
2. **Bug B（安全性，更严重）**：`/confirm` 确认端点**不等待用户点击、无条件返回 `{"result":YES}`** → 命令执行与用户确认完全脱钩，**任何设备命令（含删除/重启等破坏性操作）都会无条件执行**，用户无法阻止。此 Bug 在 Bug A 被绕过（客户端改用 `localhost`）后暴露。

---

## Bug A：3090 桥监听 IPv6 `::1`，客户端连 IPv4 `127.0.0.1` 失败

### 现象
- 用户在 App「配置」页已开启 ADB 开关，App 前台运行，但**确认弹窗从不出现**；
- 所有非只读设备命令（如 `sh /data/local/tmp/shizuku_starter`）被静默拒绝：
  ```
  USER_REJECTED: 用户未确认该命令（报备被拒）
  ```
- 重启 App 无效（桥服务并未死，只是地址不匹配）。

### 复现
```bash
# 客户端视角（容器内）——IPv4 连接被拒：
curl -s http://127.0.0.1:3090/health
# → 无响应 / Connection refused

# 桥实际监听在 IPv6 回环：
curl -s "http://[::1]:3090/health"
# → {"result":[UNAUTHORIZED]}   ← 桥活着！
```

### 证据：App 主进程监听表（/proc/<pid>/net/tcp6）
```
   5: 00000000000000000000000001000000:0C12 ... 0A ...   ← [::1]:3090 (0x0C12=3090) 确认桥，仅 IPv6 回环
```
对照（IPv4 侧）`/proc/<pid>/net/tcp` 中 `0C12` 端口**不存在**；同进程的 web 界面则监听 IPv4 回环 `0100007F:0C08` = `127.0.0.1:3080`，故 web 正常、确认桥不通。

### 根因推测
App 桥绑定 socket 时使用了 `localhost`（Android 上解析为 `::1`）或未指定 `InetAddress` 导致默认 IPv6；而 App 注入容器内的客户端脚本硬编码 `127.0.0.1`。

### 影响
确认弹窗链路完全断裂；依赖该桥的「Shell 命令确认」「App 通知/Toast」等全部不可用。

---

## Bug B：`/confirm` 端点无条件放行，确认形同虚设

### 现象
- 修复 Bug A（客户端改用 `localhost`）后，非只读命令**直接执行**，弹窗不出现或出现但结果与用户操作无关；
- 用户明确**未点允许**、或**点了拒绝**，命令仍执行成功（已多次实测，含写入 /sdcard 验证）。

### 复现（带合法 token 直调端点）
```bash
TOKEN=$(cat /root/.dsh/.bridge_token)

# ① 不带 force 参数：0.028s 返回 YES（弹窗即使出现也无等待）
curl -s "http://localhost:3090/confirm?cmd=TEST&token=$TOKEN"
# → {"result":YES}   (real 0m0.028s)

# ② 带 force=1：3.7s 返回 YES（仍不等待用户点击）
curl -s "http://localhost:3090/confirm?cmd=TEST&token=$TOKEN&force=1"
# → {"result":YES}   (real 0m3.731s)

# ③ 其他参数组合 wait=1 / force=0 / noauto=1 结果相同，均为 YES
```

### 证据：端点行为对照
| 请求 | 耗时 | 返回 |
| :--- | :--- | :--- |
| `/confirm?...&token=<合法>` | 0.028s | `{"result":YES}` |
| `/confirm?...&token=<合法>&force=1` | 3.731s | `{"result":YES}` |
| `/confirm?...&token=<合法>&wait=1` | ~0.03s | `{"result":YES}` |
| 其他端点（/health 等）不带 token | — | `{"result":[UNAUTHORIZED]}`（鉴权有效） |

### 根因推测
`/confirm` 处理器在 token 校验通过后**直接返回 YES**，未挂起请求等待用户点击；用户侧弹窗（`http-shell-acce` 线程，OkHttp 实现）即便展示，也与 API 返回结果无因果关系。设计意图应是「阻塞等待用户确认，超时默认拒绝」，实际实现成了「无条件放行」。

### 影响（严重性：**高**）
- 安全确认机制失效：`adb-shell.py` 中 `request_confirm()` 只要收到 `"YES"` 即放行，故**任意设备命令（rm /data、reboot、wipe 等）无需用户同意即可执行**；
- 结合 Bash 工具注入的「危险命令守卫」（`/root/dsh-bin/rm` 等）也走同一 3090 确认通道，同样失效。

---

## 环境与组件清单

- App：`com.dsh.client` v1.1.4 (versionCode=25)，WebView 壳，内嵌 proot 容器（容器与 App 主进程同 PID，共享网络栈）
- 设备：vivo 手机，Android 16 (SDK 36)，user 构建，无 adb root
- 确认桥线程（App 主进程内）：`http-shell` / `http-shell-acce`（HTTP 桥）、`dsha-keepalive`、`dsha-web-drain`、OkHttp 连接池
- 容器侧客户端脚本：
  - `/root/.dsh/adb-shell.py`（版本 8）— 设备命令执行 + 确认（`request_confirm()` 走 3090）
  - `/root/dsh-confirm.sh` — 备用确认脚本（走 3090，原实现**漏带 token**，属附带 Bug C）
  - `/root/dsha-device-shell-guide/lib/index.js`、`/root/dsha-task-notifier/lib/index.js` — 内置插件，硬编码 127.0.0.1:3090

### 附带 Bug C：`dsh-confirm.sh` 请求未带 token
```bash
# 原实现：不带 token，即使地址可达也会被桥以 UNAUTHORIZED 拒绝
RES=$(curl -s -m 65 -G "http://127.0.0.1:3090/confirm" --data-urlencode "cmd=$CMD")
```

---

## 修复建议（供作者参考）

### Bug A（IPv6/IPv4 不匹配）
1. 桥监听统一绑定 `127.0.0.1`（IPv4），或同时监听双栈（`::` + `127.0.0.1`）；Android 上避免使用 `localhost`（其解析优先 `::1`）；
2. 客户端脚本统一改用 `localhost`（可同时兼容 IPv4/IPv6 解析顺序），或在脚本内做双地址探测。

### Bug B（确认无条件放行）
1. `/confirm` 应**阻塞挂起**请求，等待用户点击「允许/拒绝」后返回对应结果；超时（建议 30–60s）默认返回**拒绝**（安全优先）；
2. 若设计为「异步审批」（API 立即返回、App 端拦截命令执行），则需 App 端在命令执行路径上真正拦截——当前实现显然没有拦截层；
3. 建议为确认弹窗增加「本次允许/始终允许/拒绝」三态，减少打扰。

### 附带
- `dsh-confirm.sh` 补上 `token` 参数（已一并修复）。

---

## 已做的临时修复（用户侧）

将容器内 4 个文件的 `http://127.0.0.1:3090` 全部替换为 `http://localhost:3090`，并为 `dsh-confirm.sh` 补上 token：

| 文件 | 变更 |
| :--- | :--- |
| `/root/.dsh/adb-shell.py` | `127.0.0.1:3090` → `localhost:3090` |
| `/root/dsh-confirm.sh` | 同上 + 新增读取 `/root/.dsh/.bridge_token` 并随请求提交 |
| `/root/dsha-device-shell-guide/lib/index.js` | 提示文案 3 处替换 |
| `/root/dsha-task-notifier/lib/index.js` | 通知 URL 替换 |

> ⚠️ 该修复仅解决「弹窗发不出」；**Bug B（无条件放行）无法从容器侧修复**，需 App 端更新。

---

## 附：关键原始证据

### 1. 监听表（App 主进程 /proc/<pid>/net）
```
tcp  (IPv4):  00000000:4783 0A
              00000000:36E3 0A
              0100007F:0C08 0A   ← 127.0.0.1:3080 (web)
              0100007F:CEEC 0A
tcp6 (IPv6):  0000000000000000FFFF00000100007F:B5C3 0A
              0000000000000000FFFF00000100007F:9779 0A
              00000000000000000000000000000000:27CF 0A
              00000000000000000000000000000000:A5F9 0A
              0000000000000000FFFF00000100007F:7B38 0A
              00000000000000000000000001000000:0C12 0A   ← [::1]:3090 (确认桥, 仅IPv6回环)
```

### 2. 桥线程（App 主进程）
```
http-shell
http-shell-acce
dsha-keepalive
dsha-web-drain
OkHttp Connecti
```

### 3. /confirm 实测时序（三次）
```
curl /confirm (无force)   → {"result":YES}   real 0m0.028s
curl /confirm (force=1)   → {"result":YES}   real 0m3.731s
curl /confirm (请点拒绝)  → {"result":YES}   real 0m0.087s   ← 用户点拒绝仍 YES
```

### 4. 用户侧验证（写入 /sdcard 均成功，用户未点允许/未见弹窗）
```
$ cat /sdcard/dsh_confirm_test.txt
confirm_test
```

### 5. 用户点「拒绝」后命令仍执行（决定性证据）
```
测试1：adb-shell 执行 `echo confirm_test > /sdcard/dsh_confirm_test.txt`
  → 确认弹窗未出现，命令执行，文件写入 confirm_test
测试2：adb-shell 执行文件拷贝命令（echo ... | base64 -d > /sdcard/Download/...）
  → 确认弹窗出现，用户点击「拒绝」
  → 命令仍执行成功（目标文件已创建）
测试3（最严重）：同一拷贝命令再次执行
  → 确认弹窗未出现，用户未给予任何权限
  → 命令仍执行成功（目标文件完整写入 8784 字节）
结论：用户点击「允许/拒绝」与命令是否执行完全无关——确认弹窗为「告知型」，
     且出现与否不稳定；任何设备命令均可无授权执行，确认机制 100% 失效。
```
