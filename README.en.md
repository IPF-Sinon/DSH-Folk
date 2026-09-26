<div align="center">

# DSH-Folk

[简体中文](./README.md) | [English](./README.en.md)

**A launcher for running DeepSeek Harness on Android**

[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?logo=gnu)](./LICENSE)
[![Build](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml/badge.svg)](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/IPF-Sinon/DSH-Folk?logo=github)](https://github.com/IPF-Sinon/DSH-Folk/releases/latest)

</div>

DSH-Folk puts [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) (a Node.js coding-agent CLI) on your phone:
the app downloads a Linux container runtime (arm64 or x86_64, per device architecture), starts `dsh web` inside it with proot / proroot, and you open its Web UI right on the phone.

No root and no Termux required. root / Shizuku / wireless ADB are used automatically when present, to relax certain restricted operations.

## Table of Contents

- [Preview](#preview)
- [What It Can Do Now](#what-it-can-do-now)
- [Requirements](#requirements)
- [Installation](#installation)
- [Runtime management](#runtime-management)
- [Deep dives](#deep-dives)
- [Project Structure](#project-structure)
- [Acknowledgments](#acknowledgments) · [License](#license) · [Links](#links) · [Community](#community)

The detailed implementation notes are split under `docs/`, one file per topic (see [Deep dives](#deep-dives)).

## Preview

<div align="center">

| Home | Terminal |
| :---: | :---: |
| <img src="docs/screenshots/home.jpg" width="260" alt="Home: start / stop, runtime mode, permission channel and startup log"> | <img src="docs/screenshots/terminal.jpg" width="260" alt="Terminal: a real PTY inside the container with ESC / TAB / CTRL / arrow extension keys"> |
| **Plugins** | **Settings** |
| <img src="docs/screenshots/plugins.jpg" width="260" alt="Plugins: installed list showing downloads, stars and updatable status"> | <img src="docs/screenshots/settings.jpg" width="260" alt="Settings: General / Appearance / Behavior / Features / Security / Backup / Plugins / Multimedia"> |

</div>

## What It Can Do Now

| Page | Description |
| --- | --- |
| **Home** | Start / stop / restart DSH with one tap; shows the current startup stage, Web UI address, runtime mode, and permission channel, with a copyable startup log |
| **Terminal** | A real PTY terminal inside the container (based on Termux's `terminal-view`), opening directly into container `bash` |
| **Plugins** | Manage DSH plugins inside the container; shows weekly npm downloads, GitHub stars, and dsh-market likes; includes a built-in plugin store (downloads the complete catalog and searches it locally, 4,000+ entries), surfaces upstream-scanned capabilities and safety red-lines, and supports local .tgz installation. After installation, a temporary port verifies that the plugin tree can load; failed plugins are uninstalled automatically |
| **Settings** | General / Appearance / Behavior / Features / Security / Backup / Plugins / Multimedia; the UI theme system is inherited from FolkPatch (`theme.json` is fully compatible) |

The theme store entry is in the upper-right corner of **Settings → Appearance**; theme archive (`.fpt`) export and import are in the store page's top bar.

Choose the UI language under **Settings → General → Language**, independently of the system language. The startup log, notification bar, Toast messages, and errors from both bridges all follow this setting, not the system language.

**Configuration backup** uses the same export format as the `dsh-config-manager` plugin on DSH desktop, so a zip exported on a phone can be imported directly on a computer and vice versa; credential values are not exported by default, and optional whole-archive AES-256-GCM encryption is available. Why the middle step is done App-side: see [Why the App Encrypts Backups Itself](docs/backup.en.md).

## Requirements

- Android 8.0 (API 26) or later
- An **arm64-v8a** or **x86_64** device (32-bit is not supported)
- An internet connection on first launch to download the runtime (~150 MB compressed, ~600 MB extracted; a mirror can be selected in Settings, or automatic speed testing used)
- At least 2 GB of free storage is recommended

After the runtime is downloaded on first launch, four plugins are preinstalled automatically: `dsh-web-mobile` (mobile adaptation), `dshmarket` (plugin marketplace inside the WebUI),
`dsh-config-manager` (**required by the configuration backup feature**), and `dsh-file-upload` (drag-and-drop upload / document-to-Markdown / image OCR / voice input).
Failure does not prevent startup; plugins can be installed manually later, and upgrading from an older version auto-installs any newly added ones.

root / Shizuku / wireless ADB are all **optional** and **disabled by default**. DSH-Folk only detects and reuses existing su (Magisk / KernelSU / APatch) and already-authorized Shizuku / Sui;
it does not patch the kernel, install su, or bundle a Shizuku Server. To use one, go to **Settings → Security → Permission Channel → Preferred Channel** (or “Automatic”, choosing root > Shizuku > wireless ADB).

Once a channel is picked, both the App itself (hardware monitoring, bugreport dmesg/tombstones, restart menu) and **the AI inside the container** (via `dsh-native shell`, run by the App on its behalf) can use it; the container itself never needs root. Strictness decides whether you are asked first, and defaults to **strict** (every privileged call opens a dialog). The full permission model, the two wireless-ADB locks, and which host capabilities the AI can reach through the bridge are in [What the Container Can Access on the Host](docs/host-bridges.en.md).

## Installation

Download the APK for **your architecture** from [Releases](https://github.com/IPF-Sinon/DSH-Folk/releases/latest); the `.sha256` in the same directory can be used for verification:

- `DSH-Folk-<version>-arm64-v8a.apk` — most phones and tablets
- `DSH-Folk-<version>-x86_64.apk` — Android emulators, Android-x86, ChromeOS

Both packages are functionally identical, differing only in the bundled native binaries and the downloaded container rootfs. Installing the wrong architecture shows “Unsupported architecture” at startup and exits. When unsure: pick arm64-v8a for a phone.

You can also grab development builds from [Actions](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml): pick a successful run and download the `dsh-folk-debug-*` or `dsh-folk-release-*` artifact.

**Beta channel**: the app beta only shows prereleases after you enable **Settings → General → Accept beta updates** (off by default); the container runtime beta is a separate switch (**Settings → Features → Runtime → Accept beta runtime updates**), and the two are independent. How betas / releases are published and why (release variant, versionCode convention, not using Actions artifacts, etc.) is in [Build & Release Internals](docs/dev-notes.en.md).

The app updates itself under **Settings → General → Check for updates**: it measures latency and throughput across download channels (direct GitHub / gh-proxy mirrors), supports resumable downloads, and must pass the release's `.sha256` before installing — anything that fails is never installed.

## Runtime management

The **Settings → Features → Runtime** card:

- **Update**: one button, three uses. With no update detected it acts as “check for updates”; with one detected it first shows a confirmation (target version and preserved data) before downloading; **long-press** lists every runtime version in the repo, switchable freely (downgrade included). Versions that require a newer App are flagged and point you to update the app first — those cannot even start the container.
- **Reinstall**: re-downloads the latest runtime on the current channel, optionally keeping or wiping sessions, plugins, config, and dependency data.
- **Import**: installs a local tar.gz (trusted source), showing file name and size for confirmation first.
- **Auto-check for updates**: an independent switch, **on** by default. It checks on app launch and only prompts when a new version exists; downloads still require manual confirmation. It runs in parallel with the app-update check but queues its dialog so two “update available” dialogs never stack.

The `dsh web: http://127.0.0.1:3080/?token=…` line in the log is the token used to open the WebUI, equivalent to this instance's password (LAN access is off by default, reachable only on-device) — strip it before pasting logs for help.

During preinstall pnpm prints a screenful of `missing peer …` warnings, which is **expected** (`@deepseek-ai/dsh-*`, `react` peers are resolved by dsh itself); judge success by each plugin's trailing “preinstall complete <package>” and `[DSH-Folk-exit] 0`, not by these warnings. Runtime build, rootfs workflow, and the `minAppVersion` gate are covered in [Build & Release Internals](docs/dev-notes.en.md).

## Deep dives

Split by topic under `docs/`:

- [What the Container Can Access on the Host (dsh-fs / dsh-native)](docs/host-bridges.en.md) — the two loopback bridges, 24 native capabilities, privileged commands (`shell`), accessibility (`a11y`), and the self-elevation flow
- [Why the App Encrypts Backups Itself](docs/backup.en.md) — export/import format, the `DCA1` container, snapshot rollback of app settings, the WebDAV cloud-backup plugin, the five backup scopes
- [Start on Boot](docs/autostart.en.md) — the trade-offs of boot broadcast / accessibility / boot script, and what the two accessibility toggles each mean
- [Log Collection & Redaction](docs/logs.en.md) — bugreport file ownership, redaction, time-window trimming, and `session.lock` handling
- [How It Runs](docs/architecture.en.md) — proot/proroot, rootfs, link2symlink, pnpm, ELF closure and the rest of the run chain
- [Build & Release Internals](docs/dev-notes.en.md) — app/runtime release workflows, the beta-channel mechanism, why the changelog is a local resource

## Project Structure

```
app/src/main/java/me/bmax/apatch/
  dsh/                  runtime layer: download/install, proot startup, permission detection, wireless ADB, PTY, config backup
  ui/screen/HomeDsh.kt  Home
  ui/screen/Dsh*.kt     Terminal / Plugins / Plugin store
  ui/screen/settings/   Settings sub-pages
runtime-builder/        container rootfs build scripts (run in CI) + shared-library closure check
.github/workflows/      build.yml (APK) + runtime.yml (rootfs)
docs/                   topic-by-topic deep-dive notes
```

The internal package name stays `me.bmax.apatch` (the applicationId is `top.funcun.dshfolk`) so FolkPatch's entire theme subsystem and users' existing `theme.json` keep working without a single change.

## Acknowledgments

DSH-Folk's UI reuses FolkPatch directly; the container and runtime-delivery ideas come from DSHA / DSHM:

- [FolkPatch](https://github.com/LyraVoid/FolkPatch) — the UI foundation of this project (GPL-3.0)
- [APatch](https://github.com/bmax121/APatch) — the upstream of FolkPatch
- [DSHA](https://github.com/IPF-Sinon) — wireless ADB pairing scheme, container-run logic reference
- [DSHM](https://github.com/IPF-Sinon) — online runtime delivery and mirror speed-testing scheme
- [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) — the thing being launched
- [proot](https://github.com/proot-me/proot) / [proroot](https://github.com/coderredlab/proroot) / [Termux](https://github.com/termux/termux-app) — container execution and PTY terminal
- [Shizuku](https://github.com/RikkaApps/Shizuku) — root-free privileged channel
- [KernelSU](https://github.com/tiann/KernelSU) / [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) — UI design references

## License

[GNU General Public License v3.0](./LICENSE). This project is derived from the GPL-3.0 FolkPatch and therefore stays GPL-3.0: distribution (including modifications) must likewise be open-sourced under GPLv3 with complete source.

## Links

LINUX DO open-source community | [linux.do](https://linux.do)

## Community

- QQ group: [1109060326](https://qm.qq.com/q/t7HDoR5ACk)
- Issues: https://github.com/IPF-Sinon/DSH-Folk/issues
