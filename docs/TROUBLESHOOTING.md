# Troubleshooting

Every command below assumes `export TV_HOST=<the TV's IP address>`.

## Connecting

**`adb` says "No route to host" on macOS, but the port is open.** macOS Local Network
privacy is blocking `adb`. Don't debug it; use the Python client instead:

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install 'adb-shell[cryptography]'
export TV_CLIENT=python
```

`tools/tv.py --push` with the Python client sends files as base64 chunks through the shell and
checks the md5, because the library's own push can hang on large files.

**Port 5555 stays closed.** The TV may only offer Wireless debugging (Google TV sets often do).
Under Developer options > Wireless debugging > Pair device with pairing code, note the code and
port, then:

```sh
adb pair <tv ip>:<pairing port>      # enter the code
adb connect <tv ip>:<port shown on the Wireless debugging screen>
adb tcpip 5555
```

Android switches Wireless debugging off whenever Wi-Fi drops.

**Your assistant's sandbox can't reach the TV, but your own terminal can.** Run
`tools/relay.py <tv ip>` in a terminal, leave it running, and use
`TV_HOST=127.0.0.1 TV_PORT=15555`.

**`adb install` hangs on Android 14.** It is waiting for an on-screen confirmation.
`tools/install-launcher.sh` already avoids it: it pushes the APK and runs `pm install -r`.

## Debloat

**Every `pm disable-user` is refused.** Some firmware (Fire OS 5 on a Fire TV Box, for one)
answers `pm disable-user` with a `SecurityException`, `pm uninstall --user 0` with
`DELETE_FAILED_INTERNAL_ERROR`, and `pm hide` with a success that changes nothing.
`tools/disable.sh` checks `pm list packages -d` instead of the exit code and stops after the first
batch if nothing worked. There is nothing to debloat on such a set: use the TV's own privacy and
ad-personalisation toggles, and the ad-blocking DNS (`tools/private-dns.sh on`).

**Packages come back after a reboot.** Some firmware re-enables packages from its own list at
every boot (TCL Android 9 does this for its user center). `tools/check-reenabled.sh` lists them.
Note them in your plan instead of disabling them again after each boot.

**A factory, production or "AT" package was opened.** Never start a package whose name says
factory, production, AT or fac menu, not even to look at it: on Haier/CVTE boards opening one
flips a factory-mode flag that kills the Input key and CEC, and the next boot disables Play
services, Netflix and Chromecast, which the shell then cannot re-enable. The tools never launch
packages; `tools/classify.py --explain <pkg>` only reads `dumpsys package`. This repository has no
tested recovery for a set where one was already opened. Don't reboot and don't change anything
else; look for your board's factory-reset or service-menu instructions from the manufacturer.

**Something stopped working after a batch.** Re-enable that batch's packages one by one with
`tools/restore.sh <pkg>` and take a screenshot after each until it works again.

## Launcher

**`INSTALL_FAILED_VERIFICATION_FAILURE`.** Some China-market sets refuse every sideloaded APK.
Others refuse any package that declares a HOME activity unless it carries the manufacturer's
signature. On those sets only the debloat half is possible.

**`INSTALL_FAILED_OLDER_SDK`.** The TV runs Android older than 8.0 (SDK 26). Lowering `minSdk`
is a source change you would maintain yourself.

**Home still opens the old launcher.** List the candidates and set the default:

```sh
tools/tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
tools/tv.py 'cmd package set-home-activity app.tvhome.launcher/.MainActivity'
tools/tv.py 'dumpsys activity activities | grep mFocusedApp'
```

On some Google TV builds `set-home-activity` prints `Success` and changes nothing, and the setup
wizard's `.RecoveryActivity` (`com.google.android.tungsten.setupwraith`) keeps the Home key.
Before disabling setupwraith, run `tools/tv.py "dumpsys wifi | grep -o 'cname=[^ ]*'"`: a Wi-Fi
network saved during first setup belongs to it, and Android deletes that network the moment
setupwraith is disabled, which takes the TV off Wi-Fi and ADB. If it does, forget the network and
join it again in Settings first, so it belongs to Settings.

**CVTE boards (any `com.cvte.*` package).** Leave the stock launcher (`com.cvte.tv.launcher.*`)
enabled. Make the new launcher Home with `set-home-activity`, reboot once, and confirm Home and ADB
come back before anything else. A JVC LT-43N7115 hung on its boot animation with ADB gone after
the stock launcher was replaced and a remote-key helper was enabled before one reboot.
`tools/install-launcher.sh` refuses `--disable-stock` on CVTE boards.

**Remote buttons do nothing after the stock launcher is disabled.** On Google TV the stock
launcher also answers the app buttons (Netflix, YouTube), Input and the Settings/dashboard button.
This launcher does not include an accessibility key-remapping helper. Re-enable the stock
launcher (`tools/restore.sh <its package>`) and keep it enabled behind the new Home if you need
those buttons.

**The app grid is empty.** On Android 11 and newer, package visibility hides other apps unless the
launcher declares `<queries>`. This launcher declares them; if the grid is still empty, check that
the apps you expect have a TV (`LEANBACK_LAUNCHER`) entry, or set `include_phone_apps` to `true`
in `config.xml`.

**The Wi-Fi pill says "Wi-Fi" instead of the network name.** Android 8.1+ only shows the name to
apps with location permission, and Android 10+ also needs location switched on:

```sh
tools/tv.py 'pm grant app.tvhome.launcher android.permission.ACCESS_FINE_LOCATION'
```

**An input in the Inputs menu doesn't switch.** The menu uses the standard Android TV input
intents. Some manufacturer TV apps ignore them. Set `tv_app_package` in `config.xml` to your TV
app's package so the launcher opens it instead.

## Screen

**Screenshots are empty (0 bytes).** Live TV, HDMI and some streaming apps (Netflix) block screen
capture. That is the TV, not a broken debloat. Check what is on screen with:

```sh
tools/tv.py 'dumpsys activity activities | grep topResumedActivity'
```

**The screen looks enlarged and clipped after an install or a reboot.** Check the display
overrides before editing the launcher:

```sh
tools/tv.py 'wm size'
tools/tv.py 'wm density'
tools/tv.py 'settings get secure display_density_forced'
```

`wm size` and `wm density` show the physical value next to any override. An override left from
an old resolution experiment, or a density that comes back wrong on some MediaTek Sony firmware,
is not a launcher bug. An empty `display_density_forced` is the normal default. Compare with
`backup/<date>/wm-size.txt` and `wm-density.txt`; `wm size reset` and `wm density reset` remove
the overrides.
