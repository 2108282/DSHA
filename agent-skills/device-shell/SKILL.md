---
name: device-shell
description: Use when you need to execute shell commands on an Android device from a Linux/proot environment. Covers the ADB channel and an optional local Shizuku HTTP shell bridge.
---

# Android Device Shell

Run commands on an Android device from a Linux-based agent environment.

## Channel 1: ADB (recommended)

The device is reachable through ADB. Replace `<serial>` with the actual device serial shown by `adb devices`.

```bash
adb -s <serial> shell '<device shell command>'
```

Examples:

```bash
# identity / basic info
adb -s <serial> shell 'id; getprop ro.product.model; getprop ro.build.version.release'

# list packages
adb -s <serial> shell pm list packages

# launch an app to foreground
adb -s <serial> shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER <package>
adb -s <serial> shell am start -n <resolved-component>

# check current foreground app
adb -s <serial> shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
adb -s <serial> shell dumpsys activity activities | grep -E 'ResumedActivity'
```

Notes:

- ADB shell usually runs as `uid=2000(shell)`, not root.
- Use `am`, `pm`, `input`, `dumpsys`, `getprop`, `cmd` for Android-specific operations.

## Channel 2: Local Shizuku HTTP bridge

Some Android host apps expose a local HTTP shell bridge. A common pattern is:

```bash
curl -sG 'http://127.0.0.1:<port>/exec' --data-urlencode 'cmd=<command>'
```

Response is JSON:

```json
{"result":"<command output>\n[EXIT=<code>]"}
```

If it returns a service-not-ready marker such as `[SHIZUKU_SERVICE_NOT_READY]`, the Shizuku UserService has not been bound yet. Restart the host app/service after granting Shizuku permission, then try again.

## Channel 3: Termux environment (DSHA)

When the agent runs inside **Termux** (not proot), destructive commands are
**wrapped and require user confirmation**:

- `rm`, `dd`, `mkfs*`, `fdisk`, `reboot`, `shutdown`, `halt`, `poweroff`,
  `wipe`, `pm`, `sm`, `settings` are replaced by wrappers in `~/dsh-bin/`
  (prepended to PATH by the DSHA launcher).
- Running one triggers a `termux-dialog` confirmation popup on the device
  ("DSHA 安全确认：模型试图执行 [...]"). The command **blocks** until the
  user checks "允许" (allow) or cancels.
- If rejected, or Termux:API (`termux-api` package) is not installed, the
  wrapper exits non-zero. **Treat a non-zero exit as "user denied"** — do not
  retry the same destructive command; ask the user or choose a safer path.

Notes:

- ADB shell usually runs as `uid=2000(shell)`, not root.
- Use `am`, `pm`, `input`, `dumpsys`, `getprop`, `cmd` for Android-specific operations.

## Decision guide

- Prefer ADB when it is available; it is the most reliable channel.
- Use the local HTTP bridge only after confirming it returns real command output.
