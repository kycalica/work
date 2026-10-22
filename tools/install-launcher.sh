#!/usr/bin/env bash
# Build the launcher, install it on the TV and make it the Home app.
# Usage:
#   TV_HOST=<ip> tools/install-launcher.sh                 build, install, set as Home
#   TV_HOST=<ip> tools/install-launcher.sh --no-build      install the last build
#   TV_HOST=<ip> tools/install-launcher.sh --disable-stock also disable the stock launcher
set -uo pipefail
source "$(dirname "$0")/lib.sh"

PKG=app.tvhome.launcher
APK="$REPO_DIR/launcher/app/build/outputs/apk/debug/app-debug.apk"
build=1; disable_stock=0
for arg in "$@"; do
  case "$arg" in
    --no-build) build=0 ;;
    --disable-stock) disable_stock=1 ;;
    *) echo "unknown option $arg"; exit 1 ;;
  esac
done

# 1. Can this TV run it at all?
sdk=$(tv 'getprop ro.build.version.sdk')
min=$(grep -o 'minSdk [0-9]*' "$REPO_DIR/launcher/app/build.gradle" | grep -o '[0-9]*')
if [ "${sdk:-0}" -lt "$min" ]; then
  echo "This TV is Android SDK $sdk; the launcher needs SDK $min or newer."
  echo "The debloat still works, but the launcher cannot be installed here."
  exit 1
fi

# 2. Build.
if [ "$build" = 1 ]; then
  (cd "$REPO_DIR/launcher" && ./gradlew --quiet assembleDebug) || { echo "Build failed."; exit 1; }
fi
[ -f "$APK" ] || { echo "No APK at $APK. Build it first."; exit 1; }

# 3. Install through pm: `adb install` can hang on Android 14 waiting for an on-screen prompt.
python3 "$TOOLS_DIR/tv.py" --push "$APK" /data/local/tmp/tvhome.apk
result=$(tv 'pm install -r /data/local/tmp/tvhome.apk')
tv 'rm -f /data/local/tmp/tvhome.apk' > /dev/null
echo "$result"
case "$result" in
  *Success*) ;;
  *INSTALL_FAILED_VERIFICATION_FAILURE*)
    echo "This TV refuses every sideloaded APK. The launcher half is not possible here; keep the debloat."
    exit 1 ;;
  *INSTALL_FAILED_OLDER_SDK*)
    echo "The TV's Android version is too old for this launcher."; exit 1 ;;
  *)
    echo "Install failed. Some sets refuse any HOME app not signed by the manufacturer;"
    echo "if the message says so, the launcher half is not possible on this TV."
    exit 1 ;;
esac

# Wi-Fi network names need location permission on Android 8.1 and newer.
tv "pm grant $PKG android.permission.ACCESS_FINE_LOCATION" > /dev/null

# 4. Make it Home.
echo; echo "Apps that claim HOME:"
tv 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | grep '/'
if tv 'pm list packages' | grep -q '^package:com\.cvte\.'; then
  echo "CVTE board: the stock launcher stays enabled. Set Home, reboot once and check Home and ADB come back."
  [ "$disable_stock" = 1 ] && { echo "Refusing --disable-stock on a CVTE board."; disable_stock=0; }
fi
tv "cmd package set-home-activity $PKG/.MainActivity"
python3 "$TOOLS_DIR/tv.py" --keys HOME
sleep 2
focused=$(tv 'dumpsys activity activities' | grep -m1 -E 'mFocusedApp|topResumedActivity')
echo "Focused: $focused"
if ! grep -q "$PKG" <<< "$focused"; then
  echo "Home still opens something else. On some Google TV builds set-home-activity prints Success"
  echo "and changes nothing, or setupwraith's RecoveryActivity keeps the key. See docs/TROUBLESHOOTING.md."
  exit 1
fi
echo "The new launcher is Home."

# 5. Optionally disable the stock launcher.
if [ "$disable_stock" = 1 ]; then
  stock=$(grep '/' "$STATE_DIR/home-before.txt" 2>/dev/null | cut -d/ -f1)
  if [ -z "$stock" ] || [ "$stock" = "$PKG" ]; then
    echo "Don't know the stock launcher (run tools/backup.sh before installing)."; exit 1
  fi
  if tv 'pm list packages' | grep -q '^package:com.google.android.tungsten.setupwraith$'; then
    owner=$(tv "dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u")
    echo "Google TV setup wizard present. Saved Wi-Fi owners: $owner"
    echo "If setupwraith owns your Wi-Fi network, disabling it deletes that network and drops ADB."
    echo "It is not disabled by this script. See docs/TROUBLESHOOTING.md first."
  fi
  echo "Disabling stock launcher $stock"
  tv "pm disable-user --user 0 $stock"
  if tv 'pm list packages -d' | grep -qx "package:$stock"; then
    grep -qx "$stock" "$DISABLED_LOG" 2>/dev/null || echo "$stock" >> "$DISABLED_LOG"
  fi
  python3 "$TOOLS_DIR/tv.py" --keys HOME
  echo "Now press each remote button (Netflix, YouTube, Input, Settings): the stock launcher handled some of them."
fi
shot "$REPO_DIR/shots/launcher-home.png" || true
