#!/usr/bin/env bash
# After a reboot, list packages from state/disabled.txt that the firmware
# switched back on by itself (TCL Android 9 does this for its user center).
# Don't fight those at every boot: note them in the plan instead.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

[ -s "$DISABLED_LOG" ] || { echo "Nothing logged in state/disabled.txt yet."; exit 0; }
disabled_now=$(tv "pm list packages -d" | sed 's/^package://')
back=0
while read -r pkg; do
  [ -z "$pkg" ] && continue
  if ! grep -qx "$pkg" <<< "$disabled_now"; then
    echo "re-enabled at boot: $pkg"; back=$((back + 1))
  fi
done < "$DISABLED_LOG"
[ "$back" -eq 0 ] && echo "All $(wc -l < "$DISABLED_LOG") packages are still disabled after the reboot."
