# ADB 直连通道问题排查报告

> 场景：直连（ADB 无线调试）与 Shizuku 双通道并存，反馈「直连无法连接成功，但 Shizuku 连接正常」。
> 本报告为纯技术排查记录，不含任何个人信息。

---

## 一、环境概览

| 项目 | 值 |
|---|---|
| 设备系统 | Android 16（SDK 36） |
| CPU 架构 | arm64-v8a |
| 直连方案 | ADB 无线调试（TLS 传输），容器内 adb_shell_wifi 库直连本机端口 |
| Shizuku 方案 | App 内部 3090 桥服务（binder/socket，不依赖网络端口） |
| 相关脚本 | /root/.dsh/adb-shell.py（连接执行）、/root/.dsh/adb-pair.py（配对） |

连接流程（adb-shell.py）：

```
参数解析 → 密钥检查 → 端口选择 → 依赖检查 → 只读白名单/确认拦截 → TLS 连接执行
```

端口选择优先级：--port 参数 > connect_port 文件 > mDNS 自动发现 > 5555。

---

## 二、代码检查发现

### 高危：只读白名单过宽（安全风险）

is_readonly 判定仅取命令第一个词，以下命令类别被整体放行（不弹确认）：

| 白名单词 | 实际放行的危险子命令 |
|---|---|
| pm | pm uninstall、pm clear、pm grant、pm install |
| settings | settings put ...（改系统设置） |
| wm | wm density/size ...（改分辨率） |
| input | input tap/swipe/keyevent（模拟触控） |
| echo | echo xxx > 文件（写文件） |

建议：改为精确子命令匹配（如仅放行 pm list、settings get、wm size），或引入更细粒度规则。

### 中危：多命令串被误拦截

白名单只匹配第一个空格前的词，`input; echo ...` 这类合法多命令串因首词带分号匹配失败而被拒（fail-closed 方向正确，但误伤合法用法）。

### 中危：shell 执行无超时

dev.shell(cmd) 未设置超时，执行 logcat（不带 -d）等挂起命令会无限等待。

### 中危：输出类型假设

out.endswith('\n') 假设返回 str；若库版本变化返回 bytes 会抛 TypeError。建议增加类型兼容处理。

### 低危：依赖库版本耦合

connect(tls_priv_pem=...) 依赖 adb_shell_wifi 0.5.x 特有参数，版本不符时报误导性 CONNECT_FAIL。建议捕获 TypeError 单独提示。

### 低危：小瑕疵

- 确认理由用 # 分割，命令本身含 # 会误截（仅影响展示文案）
- mDNS 发现用端口做 key，多设备同端口会互相覆盖
- --su 未授权时本地正确拦截（ROOT_NOT_ALLOWED），手机无 root 时设备端报 su 不存在，提示可更友好

---

## 三、直连功能实测（通道正常时）

以下功能在直连正常时均实测通过：

- 设备信息查询（getprop）
- 第三方应用列表（pm list packages）
- 进程列表（ps）
- 存储查询（df）与共享目录读写（/sdcard）
- 系统设置查询（settings get）、分辨率/密度（wm size/density）
- 前台应用/电量/屏幕状态（dumpsys）
- 触控模拟（input，用法输出正常；部分 ROM 不支持 --help 属正常）
- 安全确认机制：非只读命令未确认默认拒绝（fail-closed 生效）

---

## 四、直连失败问题根因（重点）

### 复现过程

1. 将 connect_port 文件写入错误端口（模拟端口过期）
2. 执行直连 → 0.3 秒内 Connection refused，报 CONNECT_FAIL
3. 检查 connect_port → 未被回写，mDNS 自愈未触发
4. 恢复正确端口 → 直连立即恢复正常

### 根因链路

```
connect_port 文件存在且数字合法（旧端口）
        ↓
直接使用旧端口连接（跳过 mDNS 发现）
        ↓
Android 重启 / 重开无线调试 → 端口已随机变化
        ↓
Connection refused → CONNECT_FAIL（无重试）
        ↓
mDNS 自愈代码永不执行（只在文件缺失时生效，属"死代码"）
```

关键点：

1. Android 无线调试的连接端口是随机分配的，且每次重启手机或重开无线调试都会变化
2. adb-shell.py 的 mDNS 自愈分支只在 connect_port 文件缺失/解析失败时触发；文件存在但端口过期时直接连旧端口，失败后无任何重试
3. 兜底端口 5555 几乎不可能命中（无线调试端口随机），是无效兜底
4. mDNS 本身不可靠：adb-pair.py 注释自认「容器回环收不到组播时返回 0」，时灵时不灵

### 为什么 Shizuku 不受影响

Shizuku 通道走 App 内部 3090 桥（binder/本地 socket），不依赖无线调试端口，因此端口变化对它无影响。这解释了「直连失败但 Shizuku 正常」的现象。

---

## 五、修复建议

### 方案 A（最小改动，推荐）

adb-shell.py 增加「连接失败自动重试」：

```
连接失败（Connection refused 等）
    → 触发 mDNS 重发现（约 5 秒）
    → 发现新端口 → 重连 + 回写 connect_port
    → 仍未成功 → 输出明确错误提示
```

预期效果：端口过期时约 5 秒自愈，而非永久失败。

### 方案 B（配合 A）

- 移除无效的 5555 兜底（必败路径）
- 失败提示区分「端口未知/过期」与「无线调试关闭」，指引用户重开无线调试或重新配对

### 方案 C（更可靠，App 侧）

App 端在每次直连前用系统 NsdManager 刷新无线调试端口并推送写入 connect_port（App 有系统权限，比容器内 mDNS 可靠得多），容器侧仅做兜底。

---

## 六、结论

| 问题 | 结论 |
|---|---|
| 直连通道本身 | 正常，端口正确时全部功能可用 |
| 直连失败主因 | connect_port 过期 + 无重试机制 + mDNS 自愈死代码 |
| Shizuku 不受影响 | 不依赖无线调试端口 |
| 最优先修复 | 连接失败自动 mDNS 重发现重连；其次收紧只读白名单 |
