# What the Container Can Access on the Host (dsh-fs / dsh-native)

[← Back to README](../README.en.md)

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

The same loopback bridge also exposes **cloud-backup packing endpoints** (`/cloud/appdata/status`,
`/cloud/appdata/export`, `/cloud/appdata/restore`) that the `dsh-folk-cloud` plugin calls in reverse
when a backup/restore includes **app data**: app settings and appearance live in the app's private
directory where the container cannot reach them, so the app produces/consumes the full archive (reusing
the backup screen's export/import path). They share the same token and loopback guard as
`dsh-fs`/`dsh-native`; older app versions lacking them make the plugin fall back to DSH-only tiers.

`dsh-native` — invokes native capabilities through the App, 24 in total, **all disabled by default**: enable the master toggle under **Settings → Security → Native Capabilities**,
then select individual capabilities. The UI groups them into four categories according to “what this capability affects”; the lower the group, the more caution it warrants:

```
Interact with device   notify / full_screen_notify / toast / vibrate / clipboard / intent (share and open links) / tts (speech synthesis)
Read device state      device / network / phone / sensors
Personal data          media / camera / mic / location / calendar / contacts / sms / a11y (accessibility)
Change system state    volume / settings / install / usage / shell (privileged commands)
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
dsh-native shell [--su] [--timeout ms] [--] <command>   # run through the channel you picked (see below)
dsh-native a11y tree [--depth N] [--max N]        # read the current screen as a node tree
dsh-native a11y click <text-or-id> [--class C] [--index N]
dsh-native a11y tap <x> <y> | a11y swipe <x1> <y1> <x2> <y2>
dsh-native a11y text <text> [--target <text-or-id>]
dsh-native a11y global <back|home|recents|notifications|quick_settings|lock_screen>
dsh-native caps                          # which capabilities are enabled and available
dsh-native elevate <cap> <read|write|read_write|control> --reason <why> [--command <cmd>]
```

### Privileged commands (`shell`)

This is what finally makes the channel usable by the AI in the container: the App runs the command, and the agent never gains privilege itself.
The three channels differ only in who executes:

| Channel | Identity | Implementation |
| --- | --- | --- |
| root | uid 0 | persistent su shell |
| Shizuku | uid 0 (Sui/root mode) or 2000 (adb mode) | a user service running inside Shizuku's process (`newProcess` returns a library-internal type and does not compile from an app) |
| wireless ADB | 2000, uid 0 only with `--su` | forwarded to the in-container script, so its two locks still apply |

Only two levels are meaningful: **read** allows diagnostics (the **same allowlist** the in-container script uses — `tools/check-native-logic.js` asserts the two are
byte-identical), **read+write** can change device state. Strictness decides whether you are asked (see above), and every call is audited with the channel, the identity,
the strictness in force and whether the user approved it or it ran unattended. **"a channel is selected" and "a channel works" are two different things**, and the prompt states both: root that has not been
refreshed yet is "selected, one step missing" (`root_unverified`) rather than "this device has no privilege" — the latter makes the
agent give up without trying. Root no longer needs a manual refresh either: the app verifies it on launch (silent when the grant
already exists) and a denial is not retried for a day, so the prompt cannot nag. Only three cases are blocked up front: unverified
root (still attempted, the su prompt appears at call
time), unauthorized Shizuku, and unfinished ADB pairing. Selecting a channel, tapping Refresh permissions, and just granting Shizuku
each rewrite the host facts immediately — miss one and the user hits "I turned root on and it seems not to know", because that prompt
section is rendered from those facts. `dsh-native caps` carries the **read-only command list** and whether the channel is ready:
under strict strictness a wrong guess costs the user a tap, and the list has a single source shared with the host's own check.


Results carry `exit` plus `stdout`/`stderr` (truncated past 64 KB); failures are separated by
status code: `403` the channel does not allow it (`no_channel` / `adb_write_disabled` / `root_unavailable` …), `504` timed out and dropped, `429` one is already running.
Those are states, not transient errors, and the prompt says not to retry them.

### Accessibility (`a11y`)

Reads the current screen as a node tree, and can tap, swipe, type or go home on it. It acts on **whatever the user is looking at**, not on this app, so the gap between
“read” and “write” is wider than for any other capability — and it needs the user to turn on that accessibility service in system settings (until then `caps` reports
`available:false` + `no_a11y_service`).

It clicks by text or view id rather than by remembered coordinates (bounds are device specific; they are returned with the tree). Nodes are often `clickable=false` with the
real handler on a parent, so a click walks up to the nearest clickable ancestor and only falls back to tapping the centre when there is none. Secure windows (lock screen,
password fields) refuse to hand over nodes and answer `no_window` instead of failing vaguely.

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

