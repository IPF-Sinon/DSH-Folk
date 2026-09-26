# Start on Boot

[← Back to README](../README.en.md)

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

**You will see two DSH-Folk accessibility toggles in the system**, and they are not the same thing: the one above (`DshAutostartService`) exists only to be bound
by the system and deliberately has no screen-reading permission; the other one (`DshA11yService`) belongs to the native bridge's **accessibility capability** and does
enable `canRetrieveWindowContent`. They are separate because a user who turned accessibility on for boot autostart should not silently hand over “can read the content
on your screen” along with it. They do not affect each other and can each be turned off independently.

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


