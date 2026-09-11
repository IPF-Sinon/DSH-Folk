<div align="center">

# DSH-Folk

[简体中文](./README.md) | [English](./README.en.md)

**A launcher for running DeepSeek Harness on Android**

[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?logo=gnu)](./LICENSE)
[![Build](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml/badge.svg)](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/IPF-Sinon/DSH-Folk?logo=github)](https://github.com/IPF-Sinon/DSH-Folk/releases/latest)

</div>

DSH-Folk puts [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) (a Node.js coding Agent CLI) on your phone:
the app downloads a Linux container runtime (arm64 or x86_64, according to the device architecture), starts `dsh web` inside the container using proot / proroot, and then lets you open its Web UI directly on your phone.

No root or Termux is required. If root / Shizuku / wireless ADB is available, it is used automatically to relax restrictions on certain operations.

## Preview

<div align="center">

| Home | Terminal |
| :---: | :---: |
| <img src="docs/screenshots/home.jpg" width="260" alt="Home: start / stop, runtime mode, permission channel, and startup log"> | <img src="docs/screenshots/terminal.jpg" width="260" alt="Terminal: a real PTY inside the container, with ESC / TAB / CTRL / arrow extension keys"> |
| **Plugins** | **Settings** |
| <img src="docs/screenshots/plugins.jpg" width="260" alt="Plugins: installed list showing download counts, stars, and update availability"> | <img src="docs/screenshots/settings.jpg" width="260" alt="Settings: General / Appearance / Behavior / Features / Security / Backup / Plugins / Multimedia"> |

</div>

## What It Can Do Now

| Page | Description |
| --- | --- |
| **Home** | Start / stop / restart DSH with one tap; shows the current startup stage, Web UI address, runtime mode, and permission channel, with a copyable startup log |
| **Terminal** | A real PTY terminal inside the container (based on Termux's `terminal-view`), opening directly into container `bash` |
| **Plugins** | Manage DSH plugins inside the container; shows weekly npm downloads, GitHub stars, and dsh-market likes; includes a built-in plugin store (downloads the complete catalog and searches it locally, 2,600+ entries) and supports local .tgz installation. After installation, a temporary port is used to verify that the plugin tree can load; failed plugins are uninstalled automatically |
| **Settings** | General / Appearance / Behavior / Features / Security / Backup / Plugins / Multimedia; the UI theme system is inherited from FolkPatch (`theme.json` is fully compatible) |

The theme store entry is in the upper-right corner of **Settings → Appearance**; theme archive (`.fpt`) export and import are in the store page's top bar.

Choose the UI language under **Settings → General → Language**, independently of the system language (there are also several “flavor” skins: Magic Hall / Temple of Holy Light /
Back-Kitchen Console / Overworld / Main Hall of the Immortal Residence, accessible only from here). **The startup log, notification bar, Toast messages, and errors from both bridges all follow this setting**,
not the system language — below Android 13, Android applies an in-app language only to the Activity, so all of this non-Activity text obtains a Context resolved according to the in-app language through
`LocaleCtx` (see `app/src/main/java/me/bmax/apatch/util/LocaleCtx.kt`).
The bugreport's `basic.txt` records both `AppLocale` and `SystemLocale`, making it possible to determine which language a log was written in.

**Configuration backup** uses **the same export format** as the `dsh-config-manager` plugin on DSH desktop (through its loopback HTTP API, rather than a separately implemented ZIP packer),
so a zip exported on a phone can be imported directly on a computer, and vice versa. By default it exports settings / ui / providers / plugins / mcp / prompts /
skills / agentPresets / agentInstructions / workspaces / pluginFiles / credentialsStatus / self,
but not sessions (conversation history can reach hundreds of MB); credential values are not exported, and optional whole-archive AES-256-GCM encryption is available.

## Requirements

- Android 8.0 (API 26) or later
- An **arm64-v8a** or **x86_64** device (32-bit is not supported)
- An internet connection is required on first launch to download the runtime (approximately 150 MB compressed and approximately 600 MB after extraction; a mirror can be selected in Settings, or automatic speed testing can be used)
- At least 2 GB of free storage is recommended

After the runtime is downloaded on first launch, four plugins are preinstalled automatically: `dsh-web-mobile` (mobile adaptation), `dshmarket` (plugin marketplace inside the WebUI),
`dsh-config-manager` (**required by the configuration backup feature**; export/import in Settings uses its loopback API), and
`dsh-file-upload` (drag-and-drop upload / document-to-Markdown conversion / image OCR / voice input).
This step takes a few additional minutes; failure does not prevent startup, and the plugins can be installed manually from the plugin store later.
The preinstallation list tracks each package name individually, so upgrading from an older version automatically installs any newly added ones.

The app itself can be updated under Settings → General → Check for updates: it measures latency and throughput across three download channels (direct GitHub / two gh-proxy mirrors),
supports resumable downloads, and must pass validation against the release's accompanying `.sha256` before installation — anything that fails validation is never installed.

root / Shizuku / wireless ADB are all **optional** and **disabled by default**. DSH-Folk only detects and reuses existing su installations (Magisk / KernelSU / APatch) and already-authorized Shizuku / Sui on the device;
it does not patch the kernel, install su, or bundle a Shizuku Server.

“Privileged access” is **disabled by default**: the container itself does not need root (proot/proroot never do); it is needed only for several `/proc` reads in hardware monitoring,
the dmesg/tombstones sections of bugreport, and the restart menu on the Home page. To use it, go to **Settings → Security → Permission Channel → Preferred Channel**
and select one (or select “Automatic” to choose in the order root > Shizuku > wireless ADB). Users upgrading from an older version who previously authorized root are migrated automatically to “Automatic.”

After successful pairing, an `adb-shell` command is added inside the container (executing on the device as shell / uid 2000). By default, only read-only commands
(`getprop` / `dumpsys` / `ls` / `cat`, etc.) are allowed; write operations and `--su` privilege escalation must be enabled separately under **Settings → Security → Wireless ADB**.
When they are not enabled, the command is rejected and identifies where to find the relevant toggle.

## Installation

Download the APK for the **matching architecture** from [Releases](https://github.com/IPF-Sinon/DSH-Folk/releases/latest); the `.sha256` in the same directory can be used for verification:

- `DSH-Folk-<version>-arm64-v8a.apk` — the vast majority of phones and tablets
- `DSH-Folk-<version>-x86_64.apk` — Android emulators, Android-x86, and ChromeOS

The two packages have identical functionality; they differ only in the bundled native binaries and the downloaded container rootfs. Installing the wrong architecture displays
“Unsupported architecture” at startup and exits. If unsure, choose arm64-v8a for a phone.

Development builds are also available from [Actions](https://github.com/IPF-Sinon/DSH-Folk/actions/workflows/build.yml):
select a successful run and download the `dsh-folk-debug-*` or `dsh-folk-release-*` artifact.

### Beta Channel

After enabling **Settings → General → Accept beta updates**, update checks also include prereleases, which are shown with a
“Beta” badge in the UI. This is disabled by default.

The container runtime beta is a separate channel: after enabling **Settings → Features → Runtime → Accept beta runtime updates**, runtime checks switch to the
`runtime-beta-latest` rolling channel; this is disabled by default, and beta versions may be unstable. It is independent of the app beta toggle above.
Easier still: long press **Update** on the runtime card to list every published runtime version (stable channel, beta channel, archived
versions) and tap one to switch — moving to a beta or back to a specific older version uses the same entry, with no need to flip the channel first.

### Runtime management

The **Settings → Features → Runtime** card:

- **Update**: one button, three uses. With no update detected, tapping it checks for updates; with an update detected,
  it first shows a confirmation dialog (target version and what is preserved) and only downloads after you confirm;
  **long pressing** it lists every published runtime version so you can switch freely, downgrades included. A version that
  requires a newer app is flagged in the list and points at the app update instead, because installing it would not even boot.
- **Reinstall**: downloads the latest runtime from the current channel, optionally keeping or wiping sessions, plugins, configuration and dependency data.
- **Import**: installs a local tar.gz from a source you trust, showing the file name and size for confirmation first.
- **Check for runtime updates automatically**: a separate switch, on by default. When enabled the app checks for a runtime update
  right after launch and only prompts when one is found; the download still needs manual confirmation. It runs in parallel with
  the app update check, but the dialogs are queued: while the app update check is running, or while its dialog is up, the runtime
  prompt waits until that check finishes (up to date or failed) or the dialog is dismissed, so two update dialogs never stack.

pnpm prints a wall of `missing peer …` warnings while preinstalling plugins; that is **expected**:
`@deepseek-ai/dsh-*` and `react` peers are resolved by dsh itself and are never installed into the profile's
`node_modules` (installing them would fight the host's versions). Judge preinstall success by the
「预装完成 <package>」line and `[DSH-Folk-exit] 0` at the end of each plugin, not by those warnings.

The `dsh web: http://127.0.0.1:3080/?token=…` line in the log carries the token the app uses to open the WebUI.
It is the password of that instance (LAN access is off by default, so it is only reachable on the device) —
strip it before pasting logs anywhere.

pnpm inside the container is pinned to 10.x, and the runtime build writes `update-notifier=false` into npmrc:
pnpm's own 「Update available! 10.x → 12.x」 line points users at `pnpm add -g pnpm`, and 12.x is exactly the
version that was withdrawn because its launcher is not executable.

App betas are published by the **Build DSH-Folk beta** workflow (`workflow_dispatch`, with a target version such as `1.8.1`),
using tags such as `v1.8.1-beta.7` marked as GitHub prereleases. Several decisions here are intentional:

- **Betas use the release variant and the production release signature**, not a debug package. The debug variant's package name is
  `top.funcun.folkpatch.debug` (an independent app that can coexist with the production version); installing it is not an “upgrade” but adds another
  icon, and a debug signature cannot replace the production version at all. A beta must be able to replace the production version in place, or the channel serves no purpose.
- **versionCode uses the target production version's number** (`1.8.1` → `10801`), with no beta offset. It must be greater than the current production version
  (otherwise `compareVersions` considers it not an update and users are never notified), yet cannot be greater than that future production version (otherwise the production version
  cannot be installed over it when released). AOSP's `PackageManagerServiceUtils.checkDowngrade` rejects installation only when `after < before`;
  equality is allowed — “the same number as the target production version” lies exactly at the intersection of these constraints. Ordering is distinguished by the `-beta.N` in the version **name**;
  `compareVersions` understands it, and a production version sorts above a prerelease.
- **Actions artifacts are not used**. Artifact download URLs require authentication (anonymous `GET .../artifacts/<id>/zip`
  returns 401 while the list API returns 200), the output is still a zip archive, and it expires after 30 days. Release assets are the only option that allows the app to download anonymously,
  resume downloads, and verify them by sha256.
- When the toggle is off, betas are excluded using **two** checks: the `prerelease` flag and the prerelease suffix in the tag. Missing either check risks
  pushing everyone onto the beta channel, precisely what this toggle is meant to prevent.
- When the toggle is on, **query the list before `releases/latest`**. By definition, the latter skips prereleases; querying it first would return the production version,
  conclude “already up to date,” and return immediately, leaving the list no chance to be checked — making the toggle appear ineffective.

APKs are built only by GitHub Actions; no locally packaged artifacts are provided. To produce your own package, manually trigger **Build DSH-Folk** in Actions
(`workflow_dispatch`, choosing debug / release / both). A release requires the repository secrets
`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PRIVATE_PASSWORD`;
if any are missing, the build **fails immediately** rather than falling back to a debug signature — a “release” signed with a debug key installs and appears normal,
but has a different signature from the production package and therefore cannot be upgraded in place later, which is more dangerous than a failed build. A final signature self-check at the end of the build also prevents this situation.

The container runtime is generated by another workflow, **Build DSH runtime rootfs** (with optional `arch=both / arm64 / amd64`),
and its artifacts are published to the rolling tag `runtime-latest`: arm64 uses `rootfs.tar.gz` + `metadata.json`,
while x86_64 uses `rootfs-x86_64.tar.gz` + `metadata-x86_64.json` (arm64 retains the legacy unsuffixed names for compatibility with existing versions).
The app reads the corresponding `metadata*.json` for the local architecture to decide what to download.

The workflow also takes a `release_tag` input (empty derives it from the channel). The two historical tags that only
exist in the version list (`runtime-beta`, `runtime-0.1.1-rc.2`) were **republished in place** with it: replacing the
content of a tag is undetectable for installed users, but 「the list offers a runtime that turns out to be the broken
build of that era」 is clearly worse than publishing nothing. The r-revision in the version string changes, so users
who had the old content at least get one update prompt.

A runtime can declare `minAppVersion` in its `metadata.json` (auto-detected from the base version in `build.gradle.kts` at build time, manually overridable via the `workflow_dispatch` input): if the app is older than that requirement, it is asked to update the software first instead of downloading a runtime it cannot run.
The requirement of an installed runtime is persisted and released automatically after the app is upgraded; an empty field means no requirement, keeping old metadata compatible.

## What's New

The first launch after an upgrade shows a one-time “What's New” dialog listing what changed in that version. It and the first-launch guide **share the same dialog shell**
(`PagedInfoDialog`): both require exactly the same thing, and two shells would immediately begin to drift apart.

The content is a **local resource** (`R.array.changelog_items`), not the GitHub release body: the release body says
“a new version exists, and here is what it contains,” whereas this dialog must say “here is what changed in the version you are now running” — the user may currently be on an airplane,
so it must work offline.

The two dialogs are mutually exclusive, and the first-launch guide also records the current version as “What's New already shown”: a new user needs “what is this app,”
not “What's New,” and stacking both dialogs would cause them to cover each other's buttons.

The version number appears in three places (the baseline in `build.gradle.kts`, `VERSION` in `util/Changelog.kt`, and the entry text itself),
and `tools/check-changelog.js` keeps them in sync. There is also a runtime fallback: if the versions do not match, the dialog is not shown — presenting
content from the previous version under a new version number is a confident falsehood, worse than showing nothing. But that fallback means **users of the new version see nothing**,
and no one would notice, so the checker is the real line of defense.

The same batch of source checks includes `tools/check-kotlin-comments.js`: Kotlin block comments **can nest**,
so writing a block-comment opener inside a KDoc (for example a scope wildcard) opens a nested comment, and that KDoc's own
closing marker only closes the inner one — **the outer comment stays open and swallows every line of code after it**,
while the compiler reports a flood of “unresolved reference” errors that point nowhere near the real line (this project hit it once and
only a full CI build revealed it). The checker walks every Kotlin file character by character and confirms strings, templates, and
comments all close correctly. Both run before compilation in `build.yml` and `beta.yml`.

## Start on Boot

Choose one of three methods under **Settings → Features → Start on boot**. There are three not to pad out the list, but because Android's official broadcast method is largely
unreliable on many Chinese-market ROMs, while the other two each carry their own tradeoffs that users must weigh.

| Method | Requirements | Reliability |
| --- | --- | --- |
| **Boot broadcast** | Nothing | Depends on the ROM. MIUI / ColorOS / EMUI and others discard `BOOT_COMPLETED` for apps not allowlisted in “Autostart management,” and only the user can enable that allowlist entry in system settings |
| **Accessibility service** | Enable an accessibility toggle in the system | High. The system actively binds the accessibility service after boot, rebinds it after it is killed, and does not subject it to the allowlist |
| **Boot script** | root | Highest. The script is placed in the root manager's `service.d`, bypassing all of the mechanisms above entirely |

The accessibility option **borrows** the accessibility framework, so it is deliberately minimal: `DshAutostartService` has an empty event callback,
its configuration intentionally omits `canRetrieveWindowContent` (that is the capability responsible for “being able to read content on your screen”),
and it subscribes only to the lowest-frequency event type, `typeWindowStateChanged` — subscribing to no events causes some ROMs to treat it as an invalid
service and not display it. Its sole reason for existing is the fact that “the system will rebind it.”

Starting with Android 13, sideloaded apps are blocked by “restricted settings”; the accessibility toggle is grayed out and does nothing when tapped. The UI explains
how to resolve this (App info page → ⋮ → Allow restricted settings), because without a clear explanation users would simply assume the feature was broken.

For the script option, note that `service.d` runs during `late_start`, when the system is far from fully booted: `am` may not yet accept commands, and the app's
data partition is not mounted until the first unlock. The script therefore waits for `sys.boot_completed`, then retries ten times — it does not try to guess whether “the user has
unlocked,” because no property is reliable across all ROMs. Both waiting and retrying have bounded attempt counts; if exceeded, the script exits quietly, because a stuck
boot script would permanently occupy a root process.

All three methods ultimately converge on `DshAutostart.trigger`, which checks whether the currently selected method matches the trigger source. Therefore, after switching modes,
an old script left on the device or an accessibility service the user forgot to disable will not secretly start anything (the script is also actively deleted when switching away).
The container is never started if the runtime has not yet been downloaded — otherwise boot could automatically consume 120 MB of data.

**Whether to start the container at the same time** is an independent option. On: the container starts with the app and is immediately usable after boot. Off: only the notification appears and the process is prewarmed;
tap once to start, without consuming CPU during those first few seconds of boot or keeping a Node process in memory. The latter is the right choice for users who “just want it handy.”

## What the Container Can Access on the Host

In addition to dsh itself, the container includes two commands written to disk by the App. Both use the same loopback bridge bound only to `127.0.0.1` (with a random token;
other Apps cannot read this app's private directory and therefore cannot obtain the token):

`dsh-fs` — controlled access to shared storage (the root is fixed at `/sdcard`; each path segment is validated, followed by a canonical-path confirmation to prevent symlink escape):

```
dsh-fs list [path] [--recursive] [--maxDepth N] [--limit N]
dsh-fs stat <path>
dsh-fs read <path> [--offset N] [--length N]     # writes binary data to stdout
dsh-fs write <local-file> [remote-path] [--append]
dsh-fs rm <path> [-r]
dsh-fs mv <source> <destination>
dsh-fs cp <source> <destination> [--overwrite]
dsh-fs mkdir <path>
dsh-fs find <path> --glob '*.log' [--maxDepth N] [--limit N]
dsh-fs space [path]
dsh-fs health
```

Android requires “All files access” to read and write all shared storage, and this permission **can only** be granted on a system Settings page (its
protectionLevel is `signature|appop`, so the app cannot request it directly). Without it, every command above returns
`403 no_storage`, and `dsh-fs health` accurately reports `storageGranted: false`; go to
**Settings → Security → Native Capabilities → Shared Storage** and tap once to open the relevant system page.
For context, `/storage/emulated/0` is already bind-mounted into the container, so ordinary `read`/`write`/`glob` often suffice;
the bridge's value is that it provides a **narrow and auditable** path, not access itself.

`dsh-native` — invokes native capabilities through the App, 19 in total, **all disabled by default**: enable the master toggle under **Settings → Security → Native Capabilities**,
then select individual capabilities. The UI groups them into four categories according to “what this capability affects”; the lower the group, the more caution it warrants:

```
Interact with device   notify / toast / vibrate / clipboard / intent (share and open links) / tts (speech synthesis)
Read device state      device / network / phone / sensors
Personal data          media / camera / mic / location / calendar / contacts
Change system state    volume / settings / install
```

Commands:

```
dsh-native notify <title> [body] [--id N] [--ongoing]
dsh-native notify-cancel [--id N]
dsh-native toast <text>
dsh-native vibrate [--ms N] [--amplitude 1..255]
dsh-native clip get | clip set <text> [--label L]
dsh-native share <text> [--title T]
dsh-native open <https-link>
dsh-native device
dsh-native network                       # connection type / internet validation / metering / WiFi signal
dsh-native phone                         # carrier / network type / SIM / call state
dsh-native sensors list | sensors read <id>
dsh-native media list [--type image|video|audio] [--q name] [--limit N]
dsh-native media get <id> [--type image|video|audio]
dsh-native camera photo [--facing back|front] [--max N]
dsh-native tts say <text> [--lang zh-CN] [--rate 0.1..3] [--pitch 0.5..2]
dsh-native tts file <text> [--lang L]    # synthesizes a wav file under /tmp
dsh-native tts voices                    # languages this device can speak
dsh-native mic record [--ms N]
dsh-native location [--maxAge ms] [--wait ms]
dsh-native calendar list [--days N] | calendar add <title> --start <epochMs> [--minutes N]
dsh-native contacts list [--q name-or-number] [--limit N]
dsh-native volume | volume set <0..100> [--stream music|ring|alarm|notification|call|system]
dsh-native ringer <normal|vibrate|silent>
dsh-native settings | settings brightness <1..100> [--auto 0|1] | settings timeout <ms>
dsh-native settings rotation <0|1>
dsh-native install                       # whether this device allows installing unknown apps
dsh-native caps                          # which capabilities are enabled and available
dsh-native elevate <cap> <read|write|read_write|control> --reason <why> [--command <cmd>]
```

**When access is missing, the capability call itself is the request**: the bridge does not answer it with a bare 403 — it **holds
that call open**, shows the dialog in the app, and then either runs the command and hands the real result back, or fails that one call (deny, or no answer within
60 seconds). The agent never has to file a request and then call again, so "the request succeeded but the call still failed" cannot happen.

The dialog gives the user three answers — **Allow** (the level sticks), **Allow once** (exactly the next call of that capability goes through and then reverts; the
switch in settings is untouched) and **Deny** (closing the dialog counts as deny). Its body shows **the command that is about to run** verbatim in a monospace,
selectable block — what the user is judging is never "camera=write, yes or no" but "what is it about to do". The bridge rebuilds that command from the call
itself, so an ordinary call needs no extra flag; only an explicit `dsh-native elevate` takes `--command` to say what it intends to do. Several deliberate constraints:

- **An unanswered request is denied after 60 seconds.** A dialog left hanging would otherwise hold the single “one request at a time” slot forever, turning every later
  request into a 409; with a deadline the worst case degrades to “this one did not go through”.
- Because of that deadline the dialog has to be genuinely visible, so it is mounted on the main screen **and on the WebUI Activity**. With only the main screen, a user
  looking at the WebUI would never see the request — it just looks like the AI asked and nothing happened, and then the timeout quietly counts as their refusal.
- **A second dialog when Android itself is missing the permission.** The user saying “Allow” only settles the app layer; camera,
  microphone, notifications and “Modify system settings” are Android's own permissions, and the call cannot run without them. That
  dialog names what is missing, offers a jump into system settings when that is the only way to enable it, and **re-checks
  automatically when the user comes back** to the app. “Got it”, or that dialog timing out, ends the call with
  `no_android_permission` instead of pretending it worked. This stage gets a generous five minutes — the user is off hunting
  for a switch in system settings.
- “Allow once” buys exactly one call and expires after three minutes, and only a call that actually reaches the device spends it: a
  failure caused by a missing system permission leaves the grant armed, so the user does not have to answer the same question twice.
- Request state is still queryable (`pending` / `once` / `lastElevation` in `dsh-native caps`). While a call is blocked the agent is
  waiting on it anyway, so those fields are mainly for plugins and for troubleshooting; the prompt tells the agent not to re-ask
  after a deny, an expiry or a missing Android permission.

```
```

Selecting a capability immediately requests any permission it lacks; after a permission is permanently denied, the app no longer opens an empty prompt but goes directly to the system Settings page — in that situation,
`launch` returns immediately and the UI appears unresponsive, leaving users to assume the button is broken. Three capabilities use **special permissions** (`settings` requires
“Modify system settings,” lowering `volume` to silent or Do Not Disturb requires “Do Not Disturb access,” and `install` requires “Install unknown apps”);
`requestPermissions()` can never obtain these, so the system page is the only option. This is also why those three rows use different wording: they say
“tap here to open the system page,” not “tap here to grant permission.”

`tts` is the only capability in this group that **intentionally does not require the foreground**. A camera can capture only black frames in the background, and the clipboard always returns null there,
so those capabilities always return `409 not_foreground` in the background; speech is the exact opposite — when the phone is in a pocket and the user is not looking at the screen,
“have the agent say something” is most useful. It invokes the device's built-in engine (usually iFlytek or Xiaomi on devices sold in China, and Google's elsewhere),
and bundles no synthesis model. If no engine is installed, `caps` accurately reports `available:false` + `no_tts_engine`.
`tts voices` exists because **whether Chinese can be spoken depends on the device**: stripped-down overseas ROMs often lack Chinese voice data, so the agent can only ask;
it cannot guess. Speech waits synchronously until playback finishes — otherwise, if the agent invokes it again immediately, the two utterances interrupt each other.

`media get` / `camera photo` / `mic record` / `tts file` **never return binary data**: the bytes are written to the container's `/tmp/dsh-native/`,
and a path inside the container is returned for the agent to read with ordinary file tools; only the newest 32 are retained. The container rootfs is in this app's private directory, so writing there
requires no storage permission and avoids base64 expansion.

Several tradeoffs only become apparent on real devices:

- The **camera** captures without a preview (launching the system camera would mean making the user press the shutter, which is not “the agent takes a photo”). It must discard the first
  5 frames while auto-exposure converges — a single `STILL_CAPTURE` produces a black image on most devices.
- **Audio recording and photography** strictly require the foreground: Android background recording produces only **silence**, and opening the camera in the background produces only **black frames**, with neither reporting an error.
  Rather than deliver useless data, they return `409 not_foreground` directly.
- **Location** first uses a cached fix (`fresh: false` in the response), waking GNSS only when it is stale — active indoor positioning can take tens of seconds
  and still fail. Starting with Android 12, users can grant only “approximate location”; the system then obscures coordinates to kilometer-level precision, and the response's `precise: false`
  states this explicitly. The UI also shows a separate notice instead of repeatedly requesting a permission that is already granted.
- **Brightness and volume** accept percentages: raw ranges vary widely among devices (media commonly has 15 steps, calls 5), and making the agent
  query max first and calculate the value would be an unnecessary round trip. Brightness does not accept 0 (the user could not recover from a fully black screen), while volume does.
  With automatic brightness enabled, the system overwrites the value within seconds, so the response includes `autoBrightness` as a warning.
- **Every write operation returns the before and after values**: no one will restore a changed setting for the user automatically, so the agent must at least be able to explain exactly what it changed.
- **Sensors** do not become unavailable merely because permissions are missing: accelerometers, light sensors, barometers, and others require no permission; only heart rate (`BODY_SENSORS`)
  and step counting (`ACTIVITY_RECOGNITION`) do. Without permission, they disappear from the list and are reported in `needPermission`.
- **Contacts are read-only** and return only names and numbers. **Phone** exposes only the network environment, with no dialing, SMS, or IMEI — if dialing is ever needed,
  the correct form is `ACTION_DIAL` (put the number in the dialer and let the user press the call button), which belongs to the existing `intent` capability.
- **Network** bandwidth is a system **estimate**, not a measurement. The field names include `estimated` specifically to prevent them from being mistaken for speed-test results;
  `validated: false` + `connected: true` describes a captive portal situation where the device is connected but cannot access the internet.

Capabilities are separated rather than hidden behind a single master toggle because the container also runs third-party plugins installed by the user, and they all share the same token — “can call this API”
means “any code in the container can call it.” Reading the clipboard, opening share/link intents, recording audio, and taking photos remain subject to Android's background restrictions;
when the app is not in the foreground they return `409 not_foreground` rather than pretending to succeed.

Errors from both bridges have **two representations**: `error` is human-readable text following the app language (for the user), while `reason` is a stable machine code (for the agent to evaluate).
Switching the phone to English does not change program behavior.

By default, the agent **does not know** these features exist (upstream dsh has no concept of an Android host). The App installs a single-file
cordis plugin in the container, adding a section to dsh's system prompt that explains the host model/system, that `/sdcard` is already mounted, that
`dsh-fs` / `dsh-native` are available, **which** capabilities are actually enabled right now, which system permissions are missing, the device language, and whether privilege escalation is disabled.
If a capability is deselected, it disappears from the prompt in the next conversation turn, so the agent does not call an endpoint guaranteed to return 403.
Each capability also includes one sentence about its easiest-to-miss detail — calendar timestamps are milliseconds, location may be obscured to kilometer-level precision, bandwidth is estimated rather than measured,
and automatic brightness will overwrite a newly written brightness value.
That section itself is in English (matching dsh's built-in sections and avoiding biasing the model's output language); the device language is given only as a **fact**.
The section also spells out the self-service escalation flow: how to file a request, what the three dialog answers mean, that only one request may be pending at a time,
how long an unanswered request waits before counting as a deny, and which `dsh-native caps` fields tell the agent what happened (`pending` / `once` / `lastElevation`).
Without that, the model only receives a 403 `reason` and has to guess whether to wait or to try something else.
To keep the agent from knowing about these capabilities, disable the pinned **Plugins → Android native capability bridge prompt** built-in plugin. It cannot be uninstalled; disabling it makes the prompt section render as empty.

## How It Runs

```
DSH-Folk (Android app)                      ← Split by ABI: arm64-v8a / x86_64
  └─ proot / proroot                        ← Executable .so bundled in the APK
       └─ Ubuntu 24.04 rootfs               ← Downloaded online on first launch (arm64 or x86_64)
            ├─ python3                       ← Used for wireless ADB pairing, preinstalled
            ├─ git                           ← Used for git-source plugins, preinstalled
            └─ Node.js 24 + @deepseek-ai/dsh
                 └─ dsh web --port 3080      ← Listens only on 127.0.0.1 by default
                      └─ Mobile browser / open inside the app
```

Several parts have to work this way:

- Android's `app_data_file` is mounted **noexec**; only `.so` files in `nativeLibraryDir` are executable, so proot / proroot are packaged in the APK as `.so` files.
- proroot is available only for arm64 ([upstream](https://github.com/coderredlab/proroot) publishes only arm64-v8a), so x86_64 devices are fixed to proot;
  the corresponding Settings option is disabled and explains why.
- Some devices prohibit `link(2)` in private directories (real-device testing produces `AccessDeniedException`). The app first detects whether hard links work and adds `--link2symlink` to proot when they do not;
  proroot always enables it. pnpm itself uses `link()` to install packages from its content store — once links are rewritten as symbolic links,
  Node's `require.resolve` resolves realpath into the content store's flat hash directory, and the plugin's declared `./lib/client.cjs` can no longer be constructed
  (the symptom is `MissingClientBundleError` from `dsh web` after installing the plugin). In this environment, the profile's `pnpm-workspace.yaml` is therefore configured with
  `packageImportMethod: copy`, making pnpm copy real files. The cost is losing content-store deduplication and slightly increasing container size.
- `dsh plugin` only delegates to pnpm and exits with code 127 if pnpm is absent from PATH. The runtime therefore pins the self-contained
  `pnpm@10.34.5` (not `latest`: pnpm 12's npm package became a launcher that relies on postinstall to fetch a native binary, conflicting with
  the `--ignore-scripts` required for cross-architecture assembly), rebuilds the `/usr/local/bin` links from `package.json.bin`, and verifies the
  JS CLI with `node bin/pnpm.cjs --version` before packaging. This layer belongs to the runtime alone: the app no longer patches the rootfs on
  device, which would only hide the fact that the runtime is broken. The fixed revision ships as r3 on **both** channels, so existing users
  (including stable users still on 0.1.2-r2) can update the runtime in place instead of reinstalling.
- More than half of the entries in the plugin catalog use `github:owner/name` installation specifications, which pnpm resolves with `git ls-remote`, so git is also preinstalled in the rootfs.
  Note that the rootfs is built by extracting with `dpkg-deb -x` only (without running maintainer scripts, which would need to execute on the target architecture), so
  **no one resolves dpkg dependencies for us** — omit one transitive dependency from the package list, and everything looks fine during the build until the exact moment of exec on the device,
  when it reports `cannot find libxxx.so.N`. The build therefore ends with `check-elf-closure.js`: starting from git-core / Perl extensions / python3,
  it recursively resolves ELF `DT_NEEDED` entries, fails the build if any SONAME has no provider, and asserts that the entry points target the correct architecture.
- `dsh web` binds only to the loopback address by default; configuration backup uses the same loopback HTTP API. LAN access is a disabled-by-default toggle in Settings.

## Project Structure

```
app/src/main/java/me/bmax/apatch/
  dsh/                  Runtime layer: download/install, proot startup, permission detection, wireless ADB, PTY, configuration backup
  ui/screen/HomeDsh.kt  Home
  ui/screen/Dsh*.kt     Terminal / Plugins / Plugin Store
  ui/screen/settings/   Settings subpages
runtime-builder/        Container rootfs build scripts (run in CI) + shared-library closure check
.github/workflows/      build.yml (APK) + runtime.yml (rootfs)
```

The internal package name remains `me.bmax.apatch` (the applicationId is `top.funcun.dshfolk`):
this allows FolkPatch's entire theme subsystem and users' existing `theme.json` files to continue working without changing a single line.

## Acknowledgments

DSH-Folk directly reuses FolkPatch's UI, while its container and runtime delivery approach comes from DSHA / DSHM:

- [FolkPatch](https://github.com/LyraVoid/FolkPatch) — this project's UI foundation (GPL-3.0)
- [APatch](https://github.com/bmax121/APatch) — upstream of FolkPatch
- [DSHA](https://github.com/IPF-Sinon) — wireless ADB pairing approach and reference for container runtime logic
- [DSHM](https://github.com/IPF-Sinon) — runtime online delivery and mirror speed-testing approach
- [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) — the launched application itself
- [proot](https://github.com/proot-me/proot) / [proroot](https://github.com/coderredlab/proroot) / [Termux](https://github.com/termux/termux-app) — container execution and PTY terminal
- [Shizuku](https://github.com/RikkaApps/Shizuku) — rootless privileged channel
- [KernelSU](https://github.com/tiann/KernelSU) / [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) — UI design references

## License

[GNU General Public License v3.0](./LICENSE). This project is derived from GPL-3.0-licensed FolkPatch and therefore remains under GPL-3.0 as a whole:
distributions (including modified versions) must likewise be open-sourced under GPLv3 and provide the complete source code.

## Links

LINUX DO Open Source Community | [linux.do](https://linux.do) 

## Community

- QQ group: [1109060326](https://qm.qq.com/q/t7HDoR5ACk)
- Issues: https://github.com/IPF-Sinon/DSH-Folk/issues
