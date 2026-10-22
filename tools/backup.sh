#!/usr/bin/env bash
# Save the TV's state before changing anything.
# Usage: TV_HOST=<ip> tools/backup.sh
set -euo pipefail
source "$(dirname "$0")/lib.sh"

out="$REPO_DIR/backup/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
echo "Backing up to $out"
echo "If the TV asks 'Allow USB debugging?', tick 'Always allow' and press OK."

save() { local file="$1"; shift; tv "$*" > "$out/$file"; echo "  $file"; }

save packages-all.txt        'pm list packages -f'
save packages-disabled.txt   'pm list packages -d'
save packages-system.txt     'pm list packages -s'
save packages-thirdparty.txt 'pm list packages -3'
save getprop.txt             'getprop'
save settings-global.txt     'settings list global'
save settings-secure.txt     'settings list secure'
save settings-system.txt     'settings list system'
save wm-size.txt             'wm size'
save wm-density.txt          'wm density'
save density-forced.txt      'settings get secure display_density_forced'
save home-candidates.txt     'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
save home-current.txt        'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
save usb.txt                 'dumpsys usb | grep -i host_connected'
save network.txt             "dumpsys connectivity | grep -o 'Transports: [A-Z]*' | sort -u"
save wifi-owner.txt          "dumpsys wifi | grep -o 'cname=[^ ]*' | sort -u"

# Remember what was disabled before we started so restore.sh leaves it alone.
if [ ! -f "$STATE_DIR/disabled-before.txt" ]; then
  sed 's/^package://' "$out/packages-disabled.txt" > "$STATE_DIR/disabled-before.txt"
fi
grep '/' "$out/home-current.txt" | tail -n1 > "$STATE_DIR/home-before.txt" || true

sdk=$(grep -o 'ro.build.version.sdk\]: \[[0-9]*' "$out/getprop.txt" | grep -o '[0-9]*$' || true)
model=$(grep -o 'ro.product.model\]: \[[^]]*' "$out/getprop.txt" | sed 's/.*\[//' || true)
brand=$(grep -o 'ro.product.brand\]: \[[^]]*' "$out/getprop.txt" | sed 's/.*\[//' || true)
secure=$(grep -o 'ro.adb.secure\]: \[[0-9]*' "$out/getprop.txt" | grep -o '[0-9]*$' || true)
echo
echo "TV: ${brand:-?} ${model:-?}, Android SDK ${sdk:-?}"
echo "Network: $(tr '\n' ' ' < "$out/network.txt")"
echo "USB: $(tr '\n' ' ' < "$out/usb.txt")"
[ "$secure" = "0" ] && echo "WARNING: ro.adb.secure=0, any device on your network can get a shell while port 5555 is open."
echo "Done."
