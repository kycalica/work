#!/usr/bin/env bash
# Disable the packages listed in a plan, a few at a time, with a screenshot
# after each batch. Never uninstalls anything; tools/restore.sh undoes it.
# Usage: TV_HOST=<ip> tools/disable.sh plan/debloat-plan.txt [batch size, default 5]
set -uo pipefail
source "$(dirname "$0")/lib.sh"

plan="${1:?Usage: tools/disable.sh <plan file> [batch size]}"
batch_size="${2:-5}"
mkdir -p "$REPO_DIR/shots"
touch "$DISABLED_LOG"

home_package() {
  local home
  home=$(tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | grep '/' | tail -n1)
  if [ -z "$home" ]; then
    # Android 6 and older have no `cmd package`; the home stack is Stack #0.
    home=$(tv 'dumpsys activity activities' | grep -A3 'Stack #0' | grep -o '[A-Za-z0-9_.]*/[A-Za-z0-9_.$]*' | head -n1)
  fi
  echo "${home%%/*}"
}

home=$(home_package)
if [ -z "$home" ]; then
  echo "WARNING: could not tell which app is Home, so nothing protects it from being disabled."
  echo "A TV with no Home app and no recovery menu has no way back. Check the plan by hand."
else
  echo "Current Home app: $home (will not be disabled here)"
fi

mapfile -t pkgs < <(grep -v '^[[:space:]]*#' "$plan" | sed 's/[[:space:]]*#.*//; s/[[:space:]]//g' | grep -v '^$')
echo "${#pkgs[@]} packages to disable, $batch_size per batch."

done_count=0; tried=0; batch=0
for ((i = 0; i < ${#pkgs[@]}; i += batch_size)); do
  batch=$((batch + 1))
  echo; echo "Batch $batch"
  for pkg in "${pkgs[@]:i:batch_size}"; do
    if [[ "$pkg" =~ $FACTORY_RE ]]; then echo "  skip $pkg: factory package"; continue; fi
    if [[ "$pkg" =~ $PROTECTED_RE ]]; then echo "  skip $pkg: needed by the TV"; continue; fi
    if [ "$pkg" = "$home" ]; then echo "  skip $pkg: current Home app (see tools/install-launcher.sh)"; continue; fi
    tried=$((tried + 1))
    # One pm command per shell call: chained commands can stop after the first.
    result=$(tv "pm disable-user --user 0 $pkg")
    # Trust the package list, not the exit code: some firmware reports success and changes nothing.
    if tv "pm list packages -d" | grep -qx "package:$pkg"; then
      grep -qx "$pkg" "$DISABLED_LOG" || echo "$pkg" >> "$DISABLED_LOG"
      done_count=$((done_count + 1))
      echo "  disabled $pkg"
    else
      echo "  FAILED $pkg: $(echo "$result" | head -n1)"
    fi
  done
  if [ "$tried" -gt 0 ] && [ "$done_count" -eq 0 ]; then
    echo
    echo "Every package was refused. This firmware blocks pm disable-user (Fire OS 5 does),"
    echo "so there is nothing to debloat here. Use the TV's own privacy toggles and the"
    echo "ad-blocking DNS (tools/private-dns.sh on) instead. See docs/TROUBLESHOOTING.md."
    exit 1
  fi
  python3 "$TOOLS_DIR/tv.py" --keys HOME
  shot "$REPO_DIR/shots/debloat-batch-$batch.png" || true
  echo "Look at the TV (and the screenshot). Does Home, the remote and an app still work?"
  read -r -p "Enter to continue, or q to stop here: " answer
  [ "$answer" = "q" ] && break
done

echo
echo "$done_count packages disabled, logged in state/disabled.txt."
echo "Next: reboot once (tools/tv.py reboot), then run tools/check-reenabled.sh"
