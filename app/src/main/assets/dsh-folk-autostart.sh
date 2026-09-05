#!/system/bin/sh
#
# DSH-Folk autostart (rev __REV__) — installed by the app, safe to delete by hand.
#
# Lives in the root manager's service.d, so it runs as root in late_start. That is
# long before the system has finished booting: at this point ActivityManager may not
# accept commands yet, and the app's data sits on CE storage which is only mounted
# after the first unlock.
#
# There is no single property that reliably means "the user has unlocked" across
# every OEM (sys.user.0.ce_available exists on AOSP but not everywhere), so this
# does not try to guess: it waits for sys.boot_completed, then simply retries the
# start and lets `am` tell us whether it worked.
#
# Why this path exists at all when the app already has a BOOT_COMPLETED receiver:
# most Chinese ROMs drop that broadcast unless the user finds the vendor's autostart
# whitelist. Root does not go through that gate.

PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
export PATH

TAG=DSH-Folk-Autostart
CMP=__PKG__/__SERVICE__
ACT=__ACTION__

# 1. Wait for boot_completed — up to 5 minutes, then give up quietly. Giving up is
#    correct: a device that has not finished booting in five minutes has a bigger
#    problem than our container, and a stuck script would hold a root process open.
i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  i=$((i + 1))
  if [ "$i" -gt 150 ]; then
    log -p w -t "$TAG" "boot_completed never arrived; giving up"
    exit 0
  fi
  sleep 2
done

# 2. Now retry the actual start. Ten attempts, 10s apart, covers the usual case of
#    "booted but not unlocked yet" without hammering the system.
i=0
while [ "$i" -lt 10 ]; do
  out="$(am start-foreground-service -n "$CMP" -a "$ACT" 2>&1)"
  case "$out" in
    *Error*|*error*|*Exception*|*Failure*)
      log -p i -t "$TAG" "attempt $((i + 1)) not ready yet: $out"
      ;;
    *)
      log -p i -t "$TAG" "harness requested after $((i + 1)) attempt(s)"
      exit 0
      ;;
  esac
  i=$((i + 1))
  sleep 10
done

log -p w -t "$TAG" "could not start the harness after 10 attempts"
exit 0
