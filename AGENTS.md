# AGENTS.md

DSHA is the **DeepSeek Harness Android launcher**: it runs the `@deepseek-ai/dsh` Web UI on a stock, non-rooted **arm64 Android 8+** phone — no ROOT and no Termux required. This file gets an agent productive in the tree without reading it whole.

## Read order

1. This file.
2. `README.md` and `BUILD.md` — user-facing overview and build prerequisites.

## One-paragraph picture

The APK ships the Termux `proot` binary (in `jniLibs/arm64-v8a`, shipped as `libproot.so`) plus an offline **Ubuntu 24.04 arm64** rootfs. On first run the rootfs is extracted into app-private storage; afterwards `proot` chroots into it, where **Node 24 + pnpm + `@deepseek-ai/dsh@0.1.0-rc.6`** run the harness. The native UI (pure Java, Material3, bottom nav) drives the install/start/stop and hosts the Web UI preview in a system WebView (optional GeckoView).

## Tech constraints (design around these)

- **Java 17 only, no Kotlin**, single Gradle module `:app`.
- `applicationId com.dsh.client`; Java package `com.deepseekharness.app`; `minSdk 26`, `compileSdk/targetSdk 34`, NDK 26, **arm64-v8a only**.
- Version lives in `app/build.gradle` (`versionName` / `versionCode`); file `VERSION` mirrors the tag.
- The offline rootfs (`assets/offline-rootfs.*`) is **not committed** — CI generates it; local builds supply their own.

## Startup contract (do not break)

- `welcomed == false` → `WelcomeActivity` (3 pages) → `ExtractActivity` (mandatory).
- `welcomed == true` but not `isOfflineExtracted()` → `ExtractActivity` (mandatory).
- `isOfflineExtracted()` → main UI (bottom nav tabs: 启动 / 终端 / 市场 / 设置).
- `ExtractActivity → MainActivity` **must** pass `skip_extract=true`, or Main re-launches Extract forever.
- Entry into main UI keys **only** on `.offline-extracted` (not on the older `.installed` marker).
- Default runtime is RC6 (`use_rc6=true`). Do **not** place a half-cloned source tree in the rootfs alongside it — `startWeb` prefers a source tree and half a tree breaks startup.

## Architecture (bottom-up)

```
native UI (Activities/Fragments)
   → HarnessController      business core: install steps, config, start/stop Web, plugins, downloads
   → ProotBootstrap         proot exec, rootfs download/extract, offline-bundle handling
   → libproot.so + Ubuntu rootfs → Node 24 + pnpm + @deepseek-ai/dsh
```

Key files under `app/src/main/java/com/deepseekharness/app/`:

| File | Responsibility |
|---|---|
| `MainActivity.java` | Shell, startup gates, crash log, update check, backup reminder, upgrade/migration guards |
| `HarnessController.java` | ~2600-line business core. Read before editing; make minimal patches |
| `ProotBootstrap.java` | proot/env exec, offline extraction, multi-mirror downloads |
| `ExtractActivity.java` | First-run offline extraction screen |
| `BackupManager.java` | Manual + migration backups to `Download/DSHA`, restore |
| `UpdateChecker.java` | GitHub releases check |
| `HarnessService.java` | Foreground keep-alive |
| `HttpShellService.java` | `127.0.0.1:3090` command bridge |
| `ShizukuShell.java` | Real-device shell via Shizuku |
| `LanProxyService.java` | LAN access |
| `TarGzipExtractor.java` | Pure-Java tar/tar.gz extraction |
| `*Fragment.java` | Per-tab UI: Launch/Install/Config/Workspace/Terminal/Plugin/Settings |

### Rootfs paths (guest paths, reached through proot)

- Rootfs: `files/linux/ubuntu/`; extracted marker: `files/linux/.offline-extracted`.
- User data inside rootfs: `/root/.dsh` (profile config, conversations, plugins), `/root/<workdir>/.env` (default workdir `deepseek-harness`), `/root/dsh-web.log`.

### Upgrade-migration behaviour

Protecting user data across upgrades/reinstalls is split across two spots — keep both in mind when touching startup:

- `MainActivity.maybeRunUpgradeMigration()` compares stored `last_version` with `versionName`; on version change it background-packs `.dsh` + `.env` + log to `Download/DSHA/DSHA-migration-<from>-to-<to>-*.tar.gz` and, for a fresh rootfs with an existing snapshot, offers a restore (per-version "暂不" persists).
- `HarnessController.upgradeGuard()` runs at Main startup, auto-backs-up old data via `BackupManager.backupToExternal()` when `versionCode` rises; `maybePromptRestore(Activity)` (called only when `skip_extract=true`, so the rootfs is already extracted) prompts to restore the latest `DSHA-backup-*.tar.gz` into an empty rootfs via `restoreFromBackup()`.

## Plugin machinery

- Plugins live in `/root/.dsh/profiles/web/node_modules` (`PLUGIN_DIRS`), declared in `package.json` `dependencies` and in `dsh.profile.bundles`.
- Disabling renames `name` → `name.disabled`, deletes the dependency, and stashes the original source at `.dsha-src-<name>`.
- Re-enabling restores the source from the stash. If the stash is gone the caller falls back to `"*"`; `toggleScript()` (in `HarnessController`) must never write `"*"`, `"null"`, or empty into `dependencies` — a `"*"` dependency on a non-npm plugin name breaks `pnpm install`.
- Market index is `PLUGINS-ALL.md` from `awesome-dsh-plugins`, fetched through mirror URLs with a local cache fallback.

## Build & CI

- Two-stage Actions workflow (`.github/workflows/android-build.yml`):
  1. `bundle` on `ubuntu-24.04-arm` — chroot-provisions the offline rootfs (node-pty must be built on native aarch64; **never** qemu).
  2. `apk` on `ubuntu-latest` — copies the bundle into assets and runs `assembleDebug` (must strip any local `aapt2FromMavenOverride` line from `gradle.properties`).
- `protectOfflineBundle` renames `assets/offline-rootfs.tar.gz` → `.bin` before packaging (aapt silently untars `.tar.gz`).
- Artifact `dsha-debug-apk` uploads the raw `app-debug.apk`; GitHub wraps every artifact download in a ZIP, so testers must unzip before installing.
- Local build: `./build.sh` (needs Gradle 8.5, SDK 34, NDK 26, `local.properties` with `sdk.dir`).

## Coding conventions

- Comments and UI strings are **Chinese**; commit messages are Chinese with a `type:` prefix that explains **why** (match `git log`).
- `HarnessController.java` is huge — apply the smallest patch that works; don't rewrite the file.
- Match existing style: wrap risky work in try/catch, degrade to a sensible fallback, toast failures to the user.
- Do not remove the protection Gradle tasks or the extraction invariants "for cleanliness" — they exist for the reasons above.

## Known traps (verify before assuming)

- **Locating the offline bundle**: enumerate the APK zip (`ZipFile(packageCodePath)`) looking for `offline-rootfs.{tar.gz,tar,bin,tgz}` — `AssetManager.open` cannot open 300MB+ entries, and aapt may have renamed the entry to `.tar` or `.bin`.
- **Extraction**: `TarGzipExtractor.extractAuto` sniffs `1f 8b` gzip magic and falls back to raw tar.
- **`libproot.so`**: patched to fix a WebUI crash; don't replace it with a stock proot build.
- Android 10+ external writes go through MediaStore; direct `Download/DSHA` paths only work as a fallback on older or permission-less devices.