# Build & Release Internals

[← Back to README](../README.en.md)

## What's New

The first launch after an upgrade shows a one-time “What's New” dialog listing what changed in that version. It and the first-launch guide **share the same dialog shell**
(`PagedInfoDialog`): both require exactly the same thing, and two shells would immediately begin to drift apart.

The content is a **local resource** (`R.array.changelog_items`), not the GitHub release body: the release body says
“a new version exists, and here is what it contains,” whereas this dialog must say “here is what changed in the version you are now running” — the user may currently be on an airplane,
so it must work offline.

The two dialogs are mutually exclusive, and the first-launch guide also records the current version as “What's New already shown”: a new user needs “what is this app,”
not “What's New,” and stacking both dialogs would cause them to cover each other's buttons.

Two more one-shot notices hang off the same mutually exclusive chain, in a fixed order: **first-launch guide → What's New → permission-policy change → the October 8
birthday easter egg** (`ui/screen/Home.kt`). The egg is last because it matters least — the other three should come first. Its own rule lives in
`util/BirthdayEgg.kt`: it shows once on the first launch of October 8, and what it stores is the **year** (so it appears again next year, but not twice on the same
day). It is deliberately absent from `changelog_items`: announcing it in the changelog would spoil it.

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


## The app icon: the About page must show the same one as the launcher

The icon on the About page comes from `LauncherIconUtils.currentIconForeground()` — the very bitmap that
`mipmap-anydpi-v26/ic_launcher*.xml` names as its `<foreground>`, picked between the two sets according to **Settings → General → Alternative
icon**. It used to point at a standalone `drawable/about.png` (a 1.17 MB copy of the old logo): nobody remembers such a file when the icon is
redesigned, so the About page kept showing the previous generation — and “the icon does not match” is only ever noticed when a user compares
screenshots. The rule is now **resource identity** (the About page must use the adaptive icon’s foreground), so if the launcher icon is right the
About page cannot be wrong.

Two traps: referencing `R.mipmap.ic_launcher` directly resolves to an adaptive-icon XML on API 26+, which Compose’s `painterResource` cannot
paint (neither a bitmap nor a vector — it throws unsupported type), hence the per-density foreground bitmaps; and the `"use_alt_icon"` key has
**exactly one literal** (`LauncherIconUtils.KEY_USE_ALT_ICON`), because a second copy means “the switch flipped but the About page did not
follow”. The assertions live in `tools/check-launcher-icon.js`.

## The WebUI script injection pipeline (built-ins + userscripts)

There is exactly **one** way JS gets into the app own WebView: `DshWebUiActivity.installScripts` calls
`WebScripts.injections` (built-ins first, imported scripts after), and each piece goes in through its own
`WebViewCompat.addDocumentStartJavaScript` call (compiled separately, so one syntax error only kills that
piece) with `loopbackOriginRules` as the rule; when the kernel lacks document-start it falls back to one
`evaluateJavascript` per piece in `onPageStarted`. Before this version there were six unrelated paths (four
Kotlin constants, each with its own install function and `xxxShimInstalled` boolean; insets assembled by a
function; userscripts yet another path), so "what exactly gets injected" was not visible on any single page.

- **The five built-ins** live in `app/src/main/assets/webui-scripts/`; their metadata (title, summary,
  timing, switch, order) lives only in `WebScripts.BUILTINS` - timing and switches already depend on prefs
  and i18n, and a second `@run-at` inside the file would only drift. The list order is the injection order:
  the compatibility shim first (its APIs are used by other scripts and by the page), insets second (they must
  be right on the first frame).
- **Switches** (these live **only** on the Userscripts page since 2026-10 — the two rows were folded in
  from Function settings, where they were a second entry point to the same two prefs; the auto step shows
  the current kernel version, and Function settings keeps just a top-right entry icon):
  `compat` → `webui_compat_shim` (auto/on/off, auto decides by kernel), `composer` →
  `web_enter_newline`; the other three are always on (insets are a layout precondition, accessible names and
  blob downloads patch page defects). The manager rows and the Settings entries write the same pref: one
  state, two entry points.
- **The master switch only covers imported scripts.** Built-ins deliberately ignore `dsh_userscripts_on`:
  when a script blanks the page, the insets, the accessible names and the compatibility shim still have to be
  there - that is exactly what lets that native manager page bring the UI back.
- **There is one parameter channel**: insets need four CSS pixel values that change with rotation and the
  keyboard, so the payload carries `WebScripts.PARAM_MARKER` and it is replaced wholesale before injection;
  when it cannot be found the payload keeps its own all-zero fallback (valid JS - a placeholder is never
  injected). Later size changes still go through `insetUpdateScript` -> `window.__dshFolkInsets`.
- **One wrapper for both kinds**: `Userscripts.blob` (GM_* + idempotence sentinel + try/catch);
  `@run-at start` runs synchronously, so built-ins still get document-start semantics. Built-in ids carry the
  `builtin:` prefix and `setEnabled` rejects it - userscripts cannot flip their switches.

Gates: `tools/check-web-shim.js` now reads the assets (it used to slice the JS out of Kotlin constants) and
**really runs** each payload in a fake old kernel / fake DOM in Node, still asserting the API-to-version table
in reverse; it also checks that no shim JS is left in Kotlin, that there is exactly one injector and one
fallback, and that the registry and the assets are one-to-one. `tools/check-userscripts.js` pins the
registry order, timing, switch mapping, master-switch scope, the parameter channel and the manager page
iteration. Every assertion was reverse-verified (deleting a file, reordering, hanging built-ins off the
master switch, aiming the substitution at the wrong string - all of them turn it red).

## WebView-side microphone permission (voice input in the page)

Voice input on the conversation page is `getUserMedia` **in the page** — a different path from the
container's `mic record` / `mic start` (`/native/mic/*`): this one goes through WebView's
`WebChromeClient`. Both can look fine and it still will not work, which is exactly how that report read:
RECORD_AUDIO granted in system settings, the page JS correct, and the message still "microphone permission
is not enabled":

- AOSP's default `WebChromeClient.onPermissionRequest` is `request.deny()`. If the host does not
  override it, Chromium always hears "denied" and `getUserMedia` always throws `NotAllowedError` — which
  is the error upstream's client shows that message for. `DshWebUiActivity` now overrides it: resources
  that are **already granted** are granted straight away (fast path); when the page wants the microphone
  and the system has not allowed it yet, the system permission dialog is launched and this
  `PermissionRequest` is **held** (`pendingAudioRequest`) until the answer arrives — Chromium waits for
  grant/deny. A stale pending request is denied before it is overwritten, and so is any request still
  pending when the Activity goes away (no WebView object is kept for the next load).
- Chromium M117+'s `cr_media` also requires the host to declare `MODIFY_AUDIO_SETTINGS` (normal,
  granted at install). Without it logcat says `Requires MODIFY_AUDIO_SETTINGS and RECORD_AUDIO. No audio
  device will be available for recording`, and the page gets a stream with **no audio track** — again
  without an error.

Both are silent failures, so `tools/check-web-permissions.js` pins them: both permissions in the
manifest, the override inside `WebChromeClient`, the granted fast path, holding + prompting when not
granted, answering grant/deny exactly once, and the `onDestroy` fallback.
`ActivityResultContracts.RequestPermission` must be registered in `onCreate` (before STARTED), so the
gate checks which function the registration lands in too.

## Container-side recording sessions (`mic start` / `mic stop`)

`mic/record --ms N` answers "record for N ms"; push-to-talk wants "start now, stop when I am done",
with the speaker deciding the length. `POST /native/mic/start` returns `{id, path, maxMs}` at once,
and `POST /native/mic/stop?id=` finishes and returns `{path, bytes, ms, id}`. Three shape rules (each
pinned by a gate):

- **One finish path**: `finishMic` serves both `record` and `stop` (stop → delete an empty file →
  re-check the foreground → `trimStage`); only the source of the duration differs (requested vs
  measured).
- **One state bit**: still the single `recording` AtomicBoolean (`compareAndSet` to take it, clear it
  when the start fails, clear it when the session stops) — a second lock would fork "busy".
- **Watchdog + one late answer**: at `MAX_RECORD_MS` the watchdog finishes through the same path and
  keeps the file, with the result in `micLast`; a client that stops the same id one step late gets that
  result again (the next start clears it). The id must be the current session: someone else's id is
  `409 bad_session`, and a finished session with no result is `409 no_session`.

## The script marketplace (GreasyFork)

The bottom of the Userscripts page is a native marketplace: search greasyfork.org, install with one tap
into the "mine" list (where the same toggles/deletion apply). It speaks HTTP **natively, never through the
WebView** — when a bad script blanks the page, this page still works, which is the whole point of it.

The shapes were copied from the measured API; do not "fix" them from memory: the entry point is
`https://api.greasyfork.org/<locale>/scripts.json` (every `greasyfork.org/…/scripts.json` is a
**308**, and `HttpURLConnection`'s 308 support varies by version); the response has two shapes
(`{"query":[…]}`, and a **bare `[]`** past the 2000-result window); the fields are `code_url` /
`total_installs` / `users[0].name` (there is no `author`, `installs` or `code_url_ssl`, however
tempting); locale is in the URL **path**, and anything unknown falls back to `en`. The install URL is
accepted only for **https + a greasyfork host**, and the body must contain `==UserScript==` (a server
error comes back as HTML/JSON). `tools/check-market.js` pins all of that, plus the timeouts, the 2MB
cap, the IO thread and the reload after install.

## Keeping accessibility away from this app (default: only this app's AI)

The a11y reader reads **this app's own screens too** by default (`pickRoot` only skips our own
`TYPE_SYSTEM` overlay; this app's `TYPE_APPLICATION` windows are kept on purpose — that is how the
agent normally drives the chat box in here). But when the target is another app, our own window can
pose as the active one and lead the agent into the wrong tree. Hence a three-level switch
(settings: the accessibility card; implemented as `A11yOwn`, pref key `a11y_hide_own`, defined once in
`DshEnv`):

| Level | What it blocks | Who is affected |
|---|---|---|
| `off` | nothing | — |
| `agent` (default) | `/native/a11y/…` reads and writes skip this app's own windows | only our own agent |
| `all` | plus a view-level `importantForAccessibility = noHideDescendants` | **every** accessibility service (TalkBack included) |

Enforcement points (miss one and a path stays open): `pickRoot` / `searchRoots` drop our own windows
from the candidate and search lists and must **not** fall back to our own tree (a fallback makes the
switch a no-op); `setText`'s `findFocus(FOCUS_INPUT)` is a **global** query that does not go through
`searchRoots`, so it gets its own check; all three of our own windows (main UI / WebUI page in
`onResume`, the floating overlay **before** `addView`) apply the level.

Diagnostics: the tree carries `hideOwn` (the current level), every `windows[]` entry carries `own`,
and the `no_window` note says "this is a policy" (with a way out) whenever the filter is on.

Why the wording is "best effort, not a guarantee": the view-level layer is only a **hint** to the
system (a WebView's virtual tree, a dialog's separate window, `AccessibilityNodeProvider` can still
report through), and the channel layer only covers `/native/a11y/…` — `a11y screenshot` reads pixels,
`uiautomator` via `shell` and `display` (which drags the picture into the container) are not covered.

The prompt follows along: the level goes into `host-facts.json` (`a11yHideOwn`) and `dsh-folk-host`
renders a paragraph for the agent/all levels — "this app is not readable right now, expect
`no_window`/`not_found` plus `hideOwn`, do not retry and do not route around it". Facts expire by
mtime, so flipping the switch takes effect on the **next** assemble without restarting dsh.
`tools/check-a11y-own.js` pins all of the above; `tools/check-host-prompt.js` actually runs `render()`
against that paragraph.

## Normalizing github URL forms for plugin installs (`insteadOf`)

A `github:owner/name` spec turns into **`git+ssh://git@github.com/…`** by the time pnpm sees it, and
the container has neither an ssh key nor a known_hosts — a direct attempt only yields
`Host key verification failed`. Worse, the failure crosses dependency boundaries: when pnpm installs
an npm spec (say updating `dshmarket`) it re-resolves the whole profile dependency tree, so the
preinstalled `dsh-folk-cloud` (a `github:` spec) can kill the npm install.

So `DshPluginRepo`'s `insteadOf` must cover **every form** (https / git+https / **git+ssh** / ssh /
`git@github.com:`) and rewrite all of them to one target, `<line prefix>https://github.com/` (the
prefix must not be followed by `git+ssh://…` — gh-proxy does not understand that). Two easy traps:
multiple `insteadOf` values under one target URL require `--add` (without it the later one replaces
the earlier, leaving only one form rewritten); and the `pnpm add` npm path needs this rewrite around
it too (it re-resolves git deps the same way). A baseline is also installed at startup
(`ensureGitCaAtStartup`) so git paths that never go through the install methods — dsh's own
reconcile, self-heal, and `dsh plugin` run by the agent inside the session — are covered.
`tools/check-race-channel.js` pins those five points.

## Beta channel (app / runtime)

### Beta Channel

After enabling **Settings → General → Accept beta updates**, update checks also include prereleases, which are shown with a
“Beta” badge in the UI. This is disabled by default.

The container runtime beta is a separate channel: set the **update channel slider** in the version menu to beta and runtime
checks switch to the `runtime-beta-latest` rolling channel; the default is stable, and beta versions may be unstable. It is
independent of the app beta toggle above.

## Runtime card: a switch card plus a version menu

The runtime card is now a **switch card**: tapping the row checks for updates right away (through `confirmAfterCheck`, so the
confirmation dialog only appears when a build is actually installable), long pressing opens the version menu, and the switch on
the right only owns "check for runtime updates automatically". The old row of Update / Reinstall / Import buttons and the three
small toggles moved into that menu — checking is the card tap, reinstalling is the **currently installed row**, and importing is
the menu's **bottom-left** button (which is where `AlertDialog`'s dismissButton lives anyway). There is no separate manual-check
button any more: the card tap is it, and opening the menu fetches the list, which is a check too.

Two two-position sliders sit at the top of the menu (`steps = 1` on a `Slider`, so dragging snaps to either end):

- **build**: full ↔ slim;
- **update channel**: stable ↔ beta.

Both sliders are keys of `LaunchedEffect(reloadKey, slim, beta)` — changing one refetches the list (the list content is filtered
by them, so not refetching would keep showing the previous selection). A slider commits (persist + refetch) only in
`onValueChangeFinished` and only when the position actually changed: persisting every drag frame and firing a request per frame
turns the list into a slideshow.

The filtering rule lives in `RuntimeVersion.matchesFilter`: the build type is a hard condition; the four rolling tags are split by
name; **archived versions show under both channels** because their tags carry no channel information, and hiding them from one
side would leave half the users unable to find a downgrade. The build type prefers the metadata `"flavor"` field (the build
script has always written it, the app just never parsed it) — an archived release's tag has no flavor, so tag-sniffing alone can
only call it full.

The currently installed version is **pinned on top even when the filter excludes it**: tapping it reinstalls (reusing the existing
keep-data / clean-reinstall choice), while any other version switches to it. A build requiring a newer app than the current one
is not installable and only points at the app update. `ToggleSettingCard` gained an optional `onClick` for this: when the row is
taken over by another action it is no longer a Switch for accessibility (otherwise TalkBack would announce "check for updates" as
a switch), and the `Switch` itself owns the toggle.

## Runtime card & workflow internals

Preinstalling plugins makes pnpm print a screen of `missing peer …` warnings, and that is **expected**:
`@deepseek-ai/dsh-*`, `react` and other peers are resolved by dsh itself and never end up in the profile's
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

**Stable releases** have no workflow of their own (`.github/workflows` holds only build / beta / runtime); the procedure is four manual steps:

1. Bump the version in all three places: `baseVersionName()` / `baseVersionCode()` in `build.gradle.kts` and `VERSION` in `util/Changelog.kt`, and add a
   one-line summary at the top of `changelog_items`. `tools/check-changelog.js` blocks the commit when the three disagree (it also validates the
   versionCode formula and monotonicity).
2. Push to `main` and wait for the push run of **Build DSH-Folk** to go green (a push builds debug only).
3. Manually dispatch the same workflow with `build_type=release` and take the `dsh-folk-release-<sha>` artifact: release-signed packages for both ABIs plus
   their `.sha256` files.
4. Create a **non-prerelease** release. Name the assets `DSH-Folk-<version>-<abi>.apk` and the checksum files `<apk name>.sha256` (recompute after renaming
   so the filename inside each checksum file matches) — the app’s `pickApkAsset` recognises the ABI **from the asset name** and falls back to a browser
   download when it cannot pair them; and `releases/latest` only considers non-prereleases, so marking a stable build as `prerelease` means stable users
   never see an update prompt at all.

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


## The virtual screen needs a channel, not a permission

The virtual-screen server (`displayserver/`) is a **separate process** started with `app_process`
as uid 0 (root) or 2000 (shell/Shizuku): creating a TRUSTED display with `SUPPORTS_TOUCH` and
calling the hidden `InputManager.injectInputEvent` both need system permissions such as
`INJECT_EVENTS`. A regular app cannot obtain them by asking, so there is **no such thing** as
"granting the virtual screen permission" — it needs one ready elevation channel (root / Shizuku /
wireless ADB).

That is why the guidance shown without a channel must not read "missing permission, go grant it":
that would send the user to a system page which holds no switch they need. As of 2026-10 the preview
screen evaluates `PrivilegedShell.reach(ctx)?.usable` **on entry** and again **when startup fails**;
if it does not pass, `DisplayChannelGuideDialog` explains which step is missing, using
`reach().reason` (`no_channel` / `root_unverified` / `shizuku_unauthorized` / `adb_unpaired`),
and offers two exits: the permission-channel page, and a recheck.

"Recheck" is not a synonym for "got it": the user very likely just granted Shizuku or finished ADB
pairing in the system UI, and that tap must re-evaluate and let them through — otherwise the only way
back is to leave and re-enter (a button that does nothing is worse than no button).

## The two file-access switches became one (2026-10)

There used to be two: **Shared storage** (`storage_mount`, on by default, on the Features page) and
**Mount into the workspace** (`ws_mount`, off by default, on the File access scope page). Both
answered the same question — can the container see phone storage — one for `/sdcard` inside the
container, one for the workspace file tree. Two switches over one question guarantee a contradictory
state: master off (nothing mounted) while the sub-switch is on (still visible in the workspace), with
neither side technically wrong.

Now `DshFileAccess.mountEnabled` is the only judge: both mount paths
(`ContainerRuntime.storageBinds` and `ContainerRuntime.workspaceBinds`), dsh-fs acceptance
(`DshFsBridge.storageGate`), the guest→host mapping used by file handoff, and the
`workspaceStorageMounted` host fact all consult it. The `ws_mount` key and its accessors are gone
(no migration needed: its value never expressed anything the master switch did not already cover).

The File access scope page keeps the mapping-list editor (`ws_mounts`) but no longer offers a second
switch: that section now shows whether it is currently in effect, according to the master switch. The
copy has to say so — the user sees one switch that decides two things, and a description that omits
half of it turns into "I never enabled the workspace, yet it can see my files".

## The permission tier: a layer in front of "do I need to ask" (2026-10)

The native-bridge side already had two layers: **per-capability access levels** (is this capability useful
at all, long-lived) and **restriction mode** (ask before using it). The tier is a third layer placed in
front of both: it answers "within this tier we do not negotiate". A request it refuses gets **no dialog**
— just a 403 naming the reason (`tier_readonly` / `tier_workspace`) — because asking would hand the
ceiling the user just set back to them, and their answer would override their own setting.

The four tiers live in `DshPermTier`: read-only (both bridges serve reads only), workspace writes only
(the file bridge accepts writes under /root/workspace only, the native bridge still refuses write
actions), full access (no extra ceiling: per-capability levels and restriction mode decide), custom
(the same as full access at this layer, worded differently: do not decide for me, I configure
capabilities one by one). The default is **full access**: the tier did not exist before this version, and
a default that is not the old behaviour would silently tighten someone's setup on upgrade.

The workspace-write test normalises `.` and `..` by path segment before comparing prefixes:
`/root/workspace/../../etc/passwd` looks like it is inside the workspace to a plain string prefix check. The
file bridge also looks at **every path involved in the request** (move/copy carry src and dst); checking
one of them leaves a back door — moving a file in from outside, or moving one out.

The tier is written into the host facts (`permTier`) and rendered in the prompt section: an agent that
gets a 403 without knowing where the ceiling is will treat it as a failure and retry. Changing
`dsh-folk-host.mjs` means bumping `PLUGIN_REV` and updating the content hash in `check-fs-scope.js`
(existing installs decide whether to re-materialise from the version number).
