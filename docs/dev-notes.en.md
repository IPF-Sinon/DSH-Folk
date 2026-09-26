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


## Beta channel (app / runtime)

### Beta Channel

After enabling **Settings → General → Accept beta updates**, update checks also include prereleases, which are shown with a
“Beta” badge in the UI. This is disabled by default.

The container runtime beta is a separate channel: after enabling **Settings → Features → Runtime → Accept beta runtime updates**, runtime checks switch to the
`runtime-beta-latest` rolling channel; this is disabled by default, and beta versions may be unstable. It is independent of the app beta toggle above.
Easier still: long press **Update** on the runtime card to list every published runtime version (stable channel, beta channel, archived
versions) and tap one to switch — moving to a beta or back to a specific older version uses the same entry, with no need to flip the channel first.


## Runtime card & workflow internals

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

