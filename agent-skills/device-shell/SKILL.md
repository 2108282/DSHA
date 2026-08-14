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

## Decision guide

- Prefer ADB when it is available; it is the most reliable channel.
- Use the local HTTP bridge only after confirming it returns real command output.
