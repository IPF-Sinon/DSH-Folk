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
(logcat, dmesg, the crash-dump directories) is filtered by the window, while snapshots (props, mounts,
cpuinfo, packages, defconfig) have no time dimension and are always collected in full. `kallsyms` (the
kernel symbol table, still over sixty percent of the archive after compression) is collected only when
the window actually contains a crash dump — the old test was "the dropbox directory has any file at
all", which `SYSTEM_BOOT` satisfies on every boot; that is how 4.3 MB of symbols ended up in a
ten-minute report from a device that had been up for 134 seconds.

The same `check-text-clipping` rule guards the UI: a Compose `Text` given a **bounded height**
without `verticalScroll` simply clips the extra lines — no error, no ellipsis, and the short strings
in preview never show it. The update dialog's release body hit exactly that (users saw "update
content is cut off"). Such problems only appear with long text, so a checker watches for them.

The archive draws logs from three places: the dsh **stdout** the App captures (`dsh.log`, rotated on
each service start with the previous run kept as `dsh-prev.log`), the `*.log` files dsh writes itself
inside the container (`dsh-home-logs.txt`, size-capped per file), and WebView page errors (which land
in logcat and in that log). The first alone is not enough: a service start wipes it, so a report
collected after a restart shows nothing about what went wrong before it.

Session restore **does not carry `session.lock` over**: it is runtime state ("this session is being
written"), meaningless across machines, and — worse — the grouping helper parsed it as a session
(a zero-byte file yields no zstd frame), which is how a real device reported "6 of 15 session files
are unreadable" and had all six lock files moved out of the sessions tree.
