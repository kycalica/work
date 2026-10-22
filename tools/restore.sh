#!/usr/bin/env bash
# Undo the debloat: re-enable everything tools/disable.sh and
# tools/install-launcher.sh disabled, and put the old Home app back.
# Usage:
#   TV_HOST=<ip> tools/restore.sh            re-enable everything
#   TV_HOST=<ip> tools/restore.sh <pkg>...   re-enable only these packages
set -uo pipefail
source "$(dirname "$0")/lib.sh"

if [ $# -gt 0 ]; then
  pkgs=("$@")
else
  [ -s "$DISABLED_LOG" ] || { echo "state/disabled.txt is empty: nothing to restore."; exit 0; }
  mapfile -t pkgs < <(grep -v '^$' "$DISABLED_LOG")
fi

before="$STATE_DIR/disabled-before.txt"
for pkg in "${pkgs[@]}"; do
  if [ -f "$before" ] && grep -qx "$pkg" "$before"; then
    echo "  leave $pkg: it was already disabled before the debloat"; continue
  fi
  # One pm command per shell call.
  result=$(tv "pm enable --user 0 $pkg")
  echo "  $pkg: $(echo "$result" | head -n1)"
  if [ -f "$DISABLED_LOG" ]; then
    grep -vx "$pkg" "$DISABLED_LOG" > "$DISABLED_LOG.tmp"; mv "$DISABLED_LOG.tmp" "$DISABLED_LOG"
  fi
done

if [ $# -eq 0 ] && [ -s "$STATE_DIR/home-before.txt" ]; then
  old_home=$(cat "$STATE_DIR/home-before.txt")
  echo "Setting Home back to $old_home"
  tv "cmd package set-home-activity $old_home"
fi
echo "Done. Reboot the TV to be sure everything restarts: tools/tv.py reboot"
