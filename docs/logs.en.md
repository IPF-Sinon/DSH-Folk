# Log Collection & Redaction (bugreport)

[← Back to README](../README.en.md)

### File ownership when collecting logs

Every collection file in a bugreport is **created empty by the app first** and only then written by the
root shell (`dmesg > file`, `tar -czf file`). Reason: a real crash — a file created by root is owned by
root, so the app could not write the trimmed dmesg back into it (EACCES); and since the crash happened
before the temp directory was cleaned up, the root-owned file stayed behind and every retry crashed until
app data was cleared. With the file pre-created, root's write is only a truncation and ownership stays with
the app; leftovers from older versions are cleaned up because deleting only needs directory write
permission. When adding a collection item you **must** add it to that pre-creation list —
`tools/check-bugreport-files.js` fails CI on a missing one (this class of bug only shows up on a device
with root and a chosen time window).

The archive is **redacted before it is packed**: the dsh server prints its token-bearing start URL into
its own log, the app copies that log verbatim into `dsh.log`, and the archive is meant to be shared —
that token is full access to DSH on the device. The same goes for stable device identifiers from getprop
(`persist.netd.stable_secret` and friends). All of them are replaced line by line; other diagnostics stay.

The **time window is honoured for real**, but know which items it can trim: anything with timestamps
(logcat, dmesg, the crash-dump directories, `dsh.log`) is filtered by the window, while snapshots
(props, mounts, cpuinfo, packages, defconfig) have no time dimension and are always collected in full.
`kallsyms` (the
kernel symbol table, still over sixty percent of the archive after compression) is collected only when
the window actually contains a crash dump — the old test was "the dropbox directory has any file at
all", which `SYSTEM_BOOT` satisfies on every boot; that is how 4.3 MB of symbols ended up in a
ten-minute report from a device that had been up for 134 seconds.

The same `check-text-clipping` rule guards the UI: a Compose `Text` given a **bounded height**
without `verticalScroll` simply clips the extra lines — no error, no ellipsis, and the short strings
in preview never show it. The update dialog's release body hit exactly that (users saw "update
content is cut off"). Such problems only appear with long text, so a checker watches for them.

The archive draws logs from three places: the dsh **stdout** the App captures (`dsh.log`), the `*.log`
files dsh writes itself
inside the container (`dsh-home-logs.txt`, size-capped per file), and WebView page errors (which land
in logcat and in that log). The first alone is not enough: a service start wipes it, so a report
collected after a restart shows nothing about what went wrong before it.

`dsh.log` is now **all of the app own log files** (the current one plus the `dsh-web.prev.log` rotated
out on each service start, plus any added later) merged and sorted by the per-line timestamp, then cut
to the window the user picked: start = collection time minus the window, end = the newest log line,
both ends inclusive at millisecond precision. The timestamp is written by `LogStore` at append time
(local timezone, `yyyy-MM-dd HH:mm:ss.SSS`), and pending buffered lines are flushed before reading —
otherwise the lines that matter most would be exactly the ones missing. Previously the two files were
read separately as `tail(2000)` lines: that is a line count, and it makes it impossible to tell which
file the window start falls into. Lines written by older versions carry no timestamp and cannot take
part in a strict comparison, so they are **dropped outright** (strictly by time); the number dropped
is written into the Notes of `basic.txt`. A window of `minutes <= 0` ("all") does not trim the time
range, but those untimestamped lines are **still dropped** — "all" is about the time range, not about
keeping every line.

**The capability-call audit log (`audit-log.txt`) goes into the archive too**: who called what, when, and what came back — the same data as the
on-device "privilege call log". It exists because of the 2026-10-04 accessibility report: "the service cannot find the string it just wrote with
`--target`" and "`--class` says nothing matched while the node is right there in the tree" were both one glance away with this record (request
params, status code, response body) — and the report carried none of it, so that round was spent asking back and guessing. Two edges: only
`command` is collected (each endpoint declares its own masking: notification bodies, SMS, clipboard and TTS are masked; shell and accessibility
are not, by design, because what the user reviews is *where it tapped and what it typed*), and `fullCommand` is **dropped** (the unmasked copy of
the same params is enough on-device, while the report is sent to someone else); the current file keeps its last 500 entries and the rotated
previous one its last 200 (the entries from the incident often live there), truncation is written into the file header, and it is redacted like
every other item.

Session restore **does not carry `session.lock` over**: it is runtime state ("this session is being
written"), meaningless across machines, and — worse — the grouping helper parsed it as a session
(a zero-byte file yields no zstd frame), which is how a real device reported "6 of 15 session files
are unreadable" and had all six lock files moved out of the sessions tree.
