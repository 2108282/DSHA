# ADB Shell 只读白名单过宽 — 安全分析

> 来源：ADB 直连通道代码审查发现（adb-shell.py L77-79）
> 本报告为纯技术分析，不含任何个人信息。

---

## 一、问题概述

`adb-shell.py` 在执行设备命令前，用「只读白名单」决定命令是否需要用户确认。但白名单按**命令族名**匹配而非**具体子命令**，导致 `pm`、`settings`、`wm`、`input`、`echo` 旗下的破坏性命令全部免确认直接执行，安全闸门形同虚设。

## 二、缺陷代码（原样）

```python
is_readonly = cmd.strip().split(' ', 1)[0] in (
    'getprop', 'dumpsys', 'logcat', 'ls', 'cat', 'id', 'ps', 'df', 'free',
    'pm', 'settings', 'wm', 'input', 'getevent', 'uptime', 'date', 'echo')
```

判定逻辑：只取命令第一个空格前的单词，命中即放行，不检查子命令、参数与重定向。

## 三、危害场景（逻辑模拟判定结果）

以下命令全部被判定为「只读」→ 免确认直接执行：

| 类别 | 被放行的命令 | 后果 |
|---|---|---|
| 卸载/清数据 | pm uninstall 应用 / pm clear 应用 | 应用被卸载、账号数据丢失 |
| 授权 | pm grant 应用 短信/录音权限 | 应用被授予敏感权限 |
| 改系统设置 | settings put global airplane_mode_on 1 等 | 飞行模式/定位/亮度被篡改 |
| 改分辨率 | wm density 400 / wm size 720x1280 | 屏幕 UI 错乱，甚至黑屏 |
| 模拟触控 | input tap x y / input swipe / input keyevent 26 | 无声点击，可能点到删除/支付/锁屏 |
| 写文件 | echo "x" > /sdcard/xxx | 覆盖/篡改共享目录文件 |

最危险项：`input` 模拟触控是纯副作用操作，与「只读」完全不沾边；AI 若误判坐标，用户无感知。

## 四、设计意图与缺陷根源

- 意图：减少确认打扰，让 getprop/dumpsys 等真只读命令免弹窗
- 缺陷：把「命令族名」当「只读命令」用。pm/settings/wm/input/echo 是命令族，旗下既有只读子命令也有破坏性子命令，一刀切全放行

附带问题：同一段逻辑对多命令串（如 `input; echo ...`）因首词带分号匹配失败而误拦——一边过宽（pm uninstall 放行），一边过严（合法多命令串被拒）。

## 五、修复方案

### 1. 精确子命令匹配

```python
READONLY_PREFIXES = (
    'getprop ', 'dumpsys ', 'logcat ', 'ls ', 'cat ', 'id ', 'ps ', 'df ', 'free ',
    'pm list ', 'pm path ', 'pm dump ',
    'settings get ', 'settings list ',
    'wm size', 'wm density',
    'getevent ', 'uptime', 'date',
)
```

要点：
- 白名单带子命令：`pm list` 而非 `pm`
- `input`、`pm uninstall/clear/grant`、`settings put`、`wm density <数值>` 一律需确认

### 2. 重定向符号拦截

命令串含 `>`、`<`、`>>`、`| tee` 等写入形态 → 一律需确认，防止 `echo > 文件` 绕过。

### 3. 默认确认优先

拿不准就弹确认框——确认的打扰远小于误卸载/误改设置的代价。

## 六、影响评估

- 该白名单是 agent 执行设备命令的安全闸门
- 现状：破坏性命令免确认直接执行（模拟判定 100% 命中）
- 风险主体：AI 幻觉/误判时的不可逆操作（卸载、改设置、模拟点击）
- 修复成本：低（约 10 行改动），收益：高（恢复安全闸门有效性）

## 七、验证方式

修复后可用脚本模拟判定复测：

```
pm uninstall com.x        → 需确认 ✅
pm list packages          → 免确认 ✅
settings put ...          → 需确认 ✅
settings get ...          → 免确认 ✅
input tap 500 1000        → 需确认 ✅
getprop ro.product.model  → 免确认 ✅
echo "x" > /sdcard/f      → 需确认 ✅
```
