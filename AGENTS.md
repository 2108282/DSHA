# AGENTS.md

DSHA is the **DeepSeek Harness Android launcher**: it runs the `@deepseek-ai/dsh` Web UI on a stock, non-rooted **arm64 Android 8+** phone — no ROOT and no Termux required. This file gets an agent productive in the tree without reading it whole.

## Read order

1. This file.
2. `README.md` and `BUILD.md` — user-facing overview and build prerequisites.

## One-paragraph picture

The APK ships the Termux `proot` binary (in `jniLibs/arm64-v8a`, shipped as `libproot.so`) plus an offline **Ubuntu 24.04 arm64** rootfs. On first run the rootfs is extracted into app-private storage; afterwards `proot` chroots into it, where **Node 24 + pnpm + `@deepseek-ai/dsh` (rc.8 by default)** run the harness. The native UI (pure Java, Material3, bottom nav) drives the install/start/stop and hosts the Web UI preview in a system WebView (optional GeckoView).

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
- Default runtime is the npm/prebuilt route (`use_rc6=true` — the pref name is historical, it installs rc.8). Do **not** place a half-cloned source tree in the rootfs alongside it — `startWeb` prefers a source tree and half a tree breaks startup.

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
| `HarnessController.java` | ~4500-line business core. Read before editing; make minimal patches |
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

## Self-healing scripts (`app/src/main/assets/`)

Injected into the rootfs on demand and run through proot; all are idempotent and fail soft.

| Script | Called from | Purpose |
|---|---|---|
| `heal-session.sh` → `heal-sessions.py` | `doHealSessionCorruption` (Main start + `startWeb`) | Scan `/root/.dsh/sessions`, repair missing `message.id`, isolate unrepairable logs. Matches `session.jsonl{,.zstd}` **exactly** and stores pre-fix copies under `corrupt-backup/` — an earlier prefix match re-healed its own backups and doubled the file count every launch |
| `fs-write-patch.sh` | `maybeFixFsWrite` (Main start) + `installGuard` + `startWebCommand` | Patch `dsh-fs-local` so a **new** file is published with `rename` instead of `link` (see traps) |
| `backup-prepare.py` | `BackupManager.backup` | Emit `.dsha-backup-manifest.json` and inline `link:`/`file:` plugin sources into `.dsha-plugin-src/` |
| `restore-merge.py` | `restoreFromBackup` | Locate `.dsh` at any depth, remap the workdir name, re-land inlined plugins, rewrite `link:` paths, add the `node_modules` symlinks, drop unresolvable bundles, write `.dsh/restore-report.txt` |
| `rootfs-confirm-install.sh` | `ensureDangerGuard` | `/root/dsh-bin` wrappers + `dsh-confirm.sh` (3090 bridge, dual-stack) |
| `adb-{shell,pair,setup}.{py,sh}` | `AdbBridge` | Wireless-ADB channel |
| `webui-{polyfill,degrade-patch,origin-port-patch}.sh`, `lan-bind-patch.sh`, `fix-stale-bundles.sh`, `dsh-deps-heal.sh` | pre-start self-heal in `startWebCommand` | WebView/LAN/bundle/dependency fixes |

### Version markers — bump these or old installs keep the stale copy

| Constant | Where | Bump when |
|---|---|---|
| `AdbBridge.SCRIPT_VERSION` | `/root/.dsh/script-version` | any `adb-*.py/sh` change |
| `HarnessController.GUARD_VERSION` | `/root/dsh-bin/.version` | `rootfs-confirm-install.sh` change (**must equal the number echoed at the end of that script**) |
| `HarnessController.STEP6_VERSION` | `/root/.dsh/step6.version` | anything step ⑥ installs |
| `HarnessController.BUILTIN_ASSET_VERSION` | `/root/.dsh/builtin-assets.version` | builtin plugin assets change |
| `device-shell-guide/package.json` `version` | plugin dir | that plugin's code change |

## Backup format v2 (tolerant by design)

Rule: **restore as much as possible, never fail the whole archive over one unknown entry, and tell the user what was skipped.**

- Archive holds `.dsh`, `<workdir>/.env`, `dsh-web.log`, plus (v2) `.dsha-backup-manifest.json` and `.dsha-plugin-src/`.
- Restore stages into `/root/.dsha-restore-stage`, then `restore-merge.py` merges: `.dsh` at any nesting depth, `.env` remapped onto the *current* workdir name, inlined plugins landed in `/root/plugin-src/<name>` with `link:` rewritten and `node_modules/<name>` symlinked.
- Bundles that still cannot resolve are removed from `dsh.profile.bundles` (dsh must be able to boot) and reported; those with a registry spec are printed as `MISSING_PLUGINS:` and reinstalled **silently in the background** by `autoInstallPluginsSilently`.
- Pre-existing `.dsh` is moved to `.dsh.pre-restore-<ts>` instead of deleted. Archives without a manifest still restore (heuristics).
- `TarGzipExtractor.extractLenient` skips suspicious/oversized entries (counted in `lastSkipped`) rather than aborting.

## UI design tokens

`values/dimens.xml` + `values/styles.xml` own every spacing, radius and text size; layouts must reference tokens, not literals.

- Spacing: 4 / 8 / 12 / 16 / 20 (`gap_hair` … `gap_section`, `page_pad`, `card_pad`).
- Radius: `radius_card` 18dp (containers), `radius_control` 14dp (buttons/inputs), `radius_small` 10dp, `radius_pill`.
- Text: `text_display` 24sp, `text_title` 17sp, `text_body` 15sp, `text_label` 13sp, `text_caption` 12sp (+ `text_hero` for the welcome pages).
- Backgrounds are `ripple` + `selector` drawables: press feedback **and** a disabled state. Inputs highlight their stroke on focus. Bottom-nav tint must stay `@color/nav_item_tint` (a flat `@color/primary` makes all four tabs look selected).
- `themes.xml` sets the M3 semantic colors (`colorSurface`/`colorSurfaceVariant`/`colorOutline`/`colorSecondaryContainer`/…) so Switch/CheckBox/Spinner/AlertDialog follow the app palette instead of Material's default purple. `alertDialogTheme` needs a full Dialog theme, `materialAlertDialogTheme` needs a ThemeOverlay — passing the wrong kind crashes the dialog.

## 3090 bridge endpoints (what the agent can call)

`HttpShellService` listens on `127.0.0.1:3090` (plus `[::1]`), token in `/root/.dsh/.bridge_token`, every request must carry `?token=` or `X-Token`. The rootfs side is steered by the `device-shell-guide` prompt.

| Endpoint | Purpose |
|---|---|
| `/exec?cmd=` | Shizuku shell (may be unavailable; ADB channel is the primary one) |
| `/confirm?cmd=&force=1` | Ask the user to approve a command; blocks, 60s timeout ⇒ deny |
| `/app/device` | Model, Android version, battery, network, screen, foreground flag, storage, memory |
| `/app/apps?q=&limit=&user=1` | Installed packages (`pkg<TAB>label`); needs `QUERY_ALL_PACKAGES` on Android 11+ |
| `/app/launch?pkg=` | Launch an app through `PackageManager` — no ADB needed |
| `/app/clip` / `/app/clip?text=` | Read (foreground only, OS restriction) / write the clipboard |
| `/app/ask?q=&options=a\|b\|c` | Modal question, **blocks up to 120s**, returns the chosen label |
| `/app/notify?title=&text=` | Notification (suppressed while the app is foreground) |
| `/app/toast?text=` | In-app toast |
| `/app/share?text=` \| `?path=` | System share sheet (files must live under `/sdcard`) |
| `/app/open?url=` | Open a link (http/https/geo/tel/mailto/market only) |
| `/app/vibrate?ms=` | Haptic ping when a long task finishes |
| `/app/export?path=&name=` | Copy a file into `Download/DSHA` via MediaStore (accepts guest paths like `/root/x.md`) |
| `/app/readfile?path=` | Read a text file under `/sdcard` (credential files are refused) |

Rules when adding endpoints: keep them **token-gated**, refuse paths outside `/sdcard` for file access, never expose credential files, and return plain text — `handle()` wraps whatever you return in `{"result":"…"}`, so nested JSON gets double-escaped. Blocking endpoints must have a timeout and a single-flight guard (see `askBusy`).

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

- **Hard links are forbidden in app-private storage.** `link()` under `/data/user/0/<pkg>/` fails with `AccessDeniedException` (SELinux), which is why proot needs `--link2symlink` at all. The extension emulates a link as *symlink → `.l2s.` intermediate*, stored next to the source unless `PROOT_L2S_DIR` is set. dsh publishes a **new** file with `link(temp, target)` and then deletes its staging dir — so every freshly written file became a dangling symlink (`write` reported success, the file was unreadable, `edit` was fine because it uses `rename`). Fixed by `fs-write-patch.sh` (publish new files with `rename`) plus `PROOT_L2S_DIR=<rootfs>/.l2s` as a fallback. Don't "simplify" this away.
- **Injected messages need an `id`.** dsh validates every persisted `user/assistant/tool` event via `assertMessageEventShape`; a message without a non-empty `id` makes the **whole session history** refuse to load (`lacks an identified message`). `device-shell-guide` hand-rolls a message, so it must set `id: randomUUID()` — this was the root cause behind a long run of "history unavailable" reports, and session healing only cleaned up after it.
- **Bind the 3090 bridge to `127.0.0.1` explicitly.** `InetAddress.getLoopbackAddress()` returns `::1` on Android, so the bridge listened only on IPv6 while every rootfs client dials IPv4 — the confirmation dialog could never fire and commands came back `USER_REJECTED`. A second listener on `[::1]` is kept for clients that resolve `localhost`.
- **The bridge body must be valid JSON.** `{"result":YES}` (no quotes) broke `adb-shell.py`'s `'"YES"' in body` check, so even pressing *Allow* read back as a rejection.
- **Locating the offline bundle**: enumerate the APK zip (`ZipFile(packageCodePath)`) looking for `offline-rootfs.{tar.gz,tar,bin,tgz}` — `AssetManager.open` cannot open 300MB+ entries, and aapt may have renamed the entry to `.tar` or `.bin`.
- **Extraction**: `TarGzipExtractor.extractAuto` sniffs `1f 8b` gzip magic and falls back to raw tar.
- **`libproot.so`**: patched to fix a WebUI crash; don't replace it with a stock proot build.
- Android 10+ external writes go through MediaStore; direct `Download/DSHA` paths only work as a fallback on older or permission-less devices.