---
name: screen-ocr-operator
description: Use when you need to see and operate an Android screen through an OCR/vision model plus ADB. Optimized commander workflow: one model plan, batch ADB execution, minimal round-trips, verify at milestones.
---

# Screen OCR Operator (Commander Mode)

Act as the commander: **you** issue ADB commands, **the vision model** looks at screenshots and returns a concrete operation plan. The goal is to finish screen tasks with the fewest possible screenshot/OCR round-trips.

## Roles

- **Commander (you)**: capture screenshots, call the vision model, execute ADB taps/input, verify.
- **Eyes (vision model)**: OCR the screen, identify UI elements, return coordinates and steps.
- **Hands (ADB)**: `input tap`, `input text`, `input keyevent`, `am start`, etc.

## Key optimization rules

1. **Ask the model for a complete plan, not a single action.**
   - Give the model the task + current screenshot.
   - Ask it to return a JSON list of actions with coordinates:
     ```json
     {
       "actions": [
         {"type": "tap", "x": 630, "y": 1090},
         {"type": "text", "value": "hello"},
         {"type": "key", "key": "ENTER"}
       ],
       "verify": "expected screen after actions"
     }
     ```
2. **One model call per meaningful milestone.**
   - Don't screenshot + OCR after every tap.
   - Only re-ask when the next step depends on a changed screen or after a risky action.
3. **Use Android APIs for deterministic checks first.**
   - `dumpsys window | grep mCurrentFocus` → which app is foreground.
   - `dumpsys input_method | grep mInputShown` → is keyboard open.
   - `uiautomator dump` → native UI nodes/bounds when available.
   - These are faster and more reliable than OCR for state checks.
4. **Shrink screenshots before sending to the model.**
   - Capture full screen, then downscale to max width ~720px and save as JPEG quality ~80.
   - Smaller payload = much faster API round-trip.
5. **Batch ADB commands.**
   - Combine independent shell commands into one `adb shell` invocation.
   - Keep sleeps short (`sleep 0.5`–`1`) and only wait when the UI actually needs time.
6. **Verify at the end (or at major checkpoints).**
   - Final screenshot + one model confirmation is usually enough.
   - For long tasks, verify after each phase, not after every tap.

## Fast workflow

```bash
# 1. Capture and compress
adb -s <serial> exec-out screencap -p > /tmp/screen.png
python3 - <<'PY'
from PIL import Image
im = Image.open('/tmp/screen.png').convert('RGB')
w = 720
h = round(im.height * w / im.width)
im.resize((w, h)).save('/tmp/screen.jpg', 'JPEG', quality=80)
PY

# 2. Send to the vision model for a plan
# Use an OpenAI-compatible chat/completions API with image support.
# Prompt example:
#   "这是 Android 截图。任务：<goal>。请返回 JSON：
#    {\"actions\":[{\"type\":\"tap\",\"x\":...,\"y\":...}, ...], \"verify\":\"...\"}
#    坐标基于原图 <width>x<height>。"

# 3. Execute actions from the JSON plan
adb -s <serial> shell input tap <x> <y>
adb -s <serial> shell input text '<text>'
adb -s <serial> shell input keyevent 66

# 4. Verify with one final screenshot + model
adb -s <serial> exec-out screencap -p > /tmp/final.png
# send /tmp/final.png to the model: "是否达到预期？请描述当前屏幕。"
```

## IME / typing fast path

With a Chinese IME, no need to switch to English mode. Type English characters directly, then press Enter once to submit/send; the IME will not convert the text to Chinese.

```bash
adb -s <serial> shell "input text 'hello'" # quote on the remote shell
adb -s <serial> shell input keyevent 66    # press Enter once to send
```

If a field already contains wrong text, clear it with `KEYCODE_MOVE_END` + repeated `KEYCODE_DEL`, or reopen the app.

## Vision model API reference (generic)

- Endpoint: your configured OpenAI-compatible `chat/completions` endpoint.
- Model: `<YOUR_VISION_MODEL>` (e.g., a multimodal/OCR-capable model).
- Auth: `Authorization: Bearer <YOUR_API_KEY>`
- Multimodal content: `image_url` with `data:image/jpeg;base64,...`

## When to use this skill

- Opening an app and interacting with a chat/search field.
- Reading what is on the phone screen.
- Locating buttons/input boxes when `uiautomator` cannot see them.
- Verifying that a UI action actually succeeded.
