# Android Agent Skills

Two portable skills for agents running in a Linux/proot environment that need to control an Android device.

## Contents

| Skill | Purpose |
|---|---|
| `device-shell` | Execute shell commands on an Android device via ADB, or via an optional local Shizuku HTTP bridge. |
| `screen-ocr-operator` | See and operate the Android screen through an OCR/vision model + ADB. Optimized commander workflow with minimal round-trips. |

## Install

Copy each skill folder into your agent skills directory, for example:

```bash
cp -r device-shell ~/.agents/skills/
cp -r screen-ocr-operator ~/.agents/skills/
# or into a project's .agents/skills/
```

The folder layout matches the standard skill format:

```
<skill-name>/
  SKILL.md
```

## Requirements

- `adb` available in the agent environment.
- An Android device authorized for ADB.
- For `screen-ocr-operator`: an OpenAI-compatible vision/OCR model API and your own API key.

## Notes

- Replace placeholders such as `<serial>`, `<YOUR_VISION_MODEL>`, and `<YOUR_API_KEY>` with your actual values.
- No private endpoints, keys, or device-specific identifiers are included.
