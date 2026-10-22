# Clean TV Home

Scripts to debloat an Android TV or Google TV set over ADB, and a small Apple TV style launcher to
replace the stock home screen. Everything here is reversible: packages are disabled, never
uninstalled, and `tools/restore.sh` turns them back on.

> **Status:** written without access to a TV. The scripts and the launcher have not been run on a
> real set yet, and the package categories are guesses from package names. Go step by step, take
> the screenshots, and check the plan file by hand.

Works on Android TV and Google TV (Xiaomi, TCL, Hisense, Sony, OnePlus, Realme, Nvidia Shield and
most others). It does not work on Samsung Tizen, LG webOS or Fire TV.

## What you need

- A Mac, Linux or Windows (WSL) computer on the same network as the TV, with Python 3 and `adb`
  (Android platform-tools). To build the launcher yourself you also need JDK 17 and the Android
  SDK (Android Studio installs both). You can skip the build: every push to this repository
  builds the APK on GitHub Actions (Actions tab > Build > `tvhome-launcher-debug`).
- On the TV: Settings > Device Preferences > About, press OK on **Build** 7 times. Then go back to
  Developer options and turn on **USB debugging** (and **Network/ADB debugging** if your TV shows it).
- The TV's IP address (Settings > Network). Every command below assumes:

```sh
export TV_HOST=192.168.1.50    # your TV's address
```

### Before you start: USB ports

Some boxes cut power to their USB port while USB debugging is on. A USB speaker, drive or
**USB Ethernet adapter** on that port goes dead until debugging is off again. If your TV gets its
network through a USB Ethernet adapter, do the whole job in one sitting, and check first:

```sh
tools/tv.py "dumpsys usb | grep host_connected"                     # false = adapter already gone
tools/tv.py "dumpsys connectivity | grep -o 'Transports: [A-Z]*'"   # WIFI on a wired TV = it fell back
```

## 1. Back up

```sh
tools/backup.sh
```

The first time, the TV shows **Allow USB debugging?** Tick *Always allow* and press OK. The backup
goes to `backup/<date>/`: full and disabled package lists, `getprop`, all settings, display size
and density, and the current Home app.

## 2. Plan the debloat

```sh
tools/classify.py
```

It sorts packages into telemetry, content and ad services, background services, preloaded apps
and streaming apps, then asks you, one by one, which streaming apps, voice assistant, Chromecast,
Miracast and update services you use. It writes `plan/debloat-plan.txt`. Lines without `#` will
be disabled.

Read the plan. Manufacturer package names often don't say what a package does. To see what one
contains without starting it:

```sh
tools/classify.py --explain com.example.package
```

Never launch a package whose name says factory, production, AT or fac menu (see
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)). The tools refuse to disable those, and also
anything the TV needs: the TV app and inputs, tuner, HDMI/CEC, remote and Bluetooth, keyboard,
Settings, Play Store and Play Services.

## 3. Disable, in small batches

```sh
tools/disable.sh plan/debloat-plan.txt      # 5 per batch; add a number to change it
```

After each batch it goes Home, saves a screenshot to `shots/`, and waits. Check the TV, the remote
and an app before pressing Enter. Every disabled package is logged in `state/disabled.txt`.

Then reboot once and see whether the firmware switched anything back on:

```sh
tools/tv.py reboot
tools/check-reenabled.sh
```

## 4. Optional: ad-blocking DNS

On Android 9 and newer:

```sh
tools/private-dns.sh on       # uses dns.adguard-dns.com
tools/private-dns.sh off      # undo (private_dns_mode opportunistic)
```

It blocks many ad and tracking domains, **not** YouTube ads. Play something in a streaming app
afterwards to check it still works.

## 5. The launcher

Pitch black home screen with a greeting, a large thin clock and the date top left; frosted
**Inputs**, **Wi-Fi** and **Settings** pills top right; a dock of your favourite apps in a lighter
rounded band at the bottom, with the focused app's name in large type above it and a soft glow in
a colour taken from its banner; all other apps in a grid below the dock that scrolls up when you
press Down. One glass highlight springs from tile to tile. Up and Down always move exactly one
row, Up from the dock goes to the pills, and the last focused tile is remembered. No blur, no
video and no network polling, so it stays light on a TV with about 1 GB of RAM. The typeface is
[Outfit](https://github.com/Outfitio/Outfit-Fonts) (SIL Open Font License).

### Choose your dock

Edit `launcher/app/src/main/res/values/config.xml`:

```xml
<integer name="columns">4</integer>          <!-- dock slots and grid columns: 4, 5, 6 or 7 -->
<string-array name="dock_apps">              <!-- left to right -->
    <item>com.google.android.youtube.tv</item>
    <item>com.netflix.ninja</item>
    ...
</string-array>
```

Use package names (`tools/tv.py 'pm list packages -3'`). Missing apps are skipped and empty slots
are filled with other apps. `hidden_apps` hides apps from the grid. `tv_app_package` names your
TV app if the Inputs menu can't switch inputs on its own.

### Build and install

```sh
tools/install-launcher.sh                   # build, install, make it Home
tools/install-launcher.sh --no-build        # install an APK you already built or downloaded
                                            # (put it at launcher/app/build/outputs/apk/debug/app-debug.apk)
tools/install-launcher.sh --disable-stock   # also disable the stock launcher
```

The script checks the TV's Android version first (the launcher needs Android 8.0, SDK 26),
installs with `pm install -r`, grants the permission the Wi-Fi pill needs to show the network
name, sets the default Home, presses Home and confirms with `dumpsys` that the new launcher is
really in front.

Before `--disable-stock`, read the launcher section in
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md): on Google TV the setup wizard can take Home
over and may own your saved Wi-Fi network, on CVTE boards the stock launcher must stay enabled,
and on Google TV the stock launcher also answers some remote buttons (Netflix, YouTube, Input,
Settings). After disabling it, press each of those buttons. This launcher has no key-remapping
helper, so if one stops working, re-enable the stock launcher.

### Change the wallpaper

Pitch black by default. To use a picture, copy a JPG or PNG (named `wallpaper.jpg` or
`wallpaper.png`) to the launcher's folder and restart the launcher:

```sh
tools/tv.py --push my-picture.jpg /sdcard/Android/data/app.tvhome.launcher/files/wallpaper.jpg
tools/tv.py 'am force-stop app.tvhome.launcher'
```

Remove the file to go back to black.

## Check your work

Press keys and capture the screen to see what the TV shows:

```sh
tools/tv.py --screenshot shots/01.png DPAD_DOWN DPAD_RIGHT
```

Live TV, HDMI and Netflix give empty screenshots; that's the TV, not a broken debloat.

## Restore

```sh
tools/restore.sh                       # re-enable everything and put the old Home app back
tools/restore.sh com.example.package   # re-enable one app
```

You can also re-enable an app on the TV: Settings > Apps > See all apps > Show system apps >
the app > Enable. To remove the launcher: `tools/tv.py 'pm uninstall app.tvhome.launcher'`
(after re-enabling the stock launcher).

## When you're done: close ADB

Port 5555 is plaintext, and on firmware with `ro.adb.secure=0` anyone on your network can get a
shell. On the TV:

1. Developer options: turn off **USB debugging** and **Network/ADB debugging**, and choose
   **Revoke USB debugging authorisations**.
2. Reboot the TV.
3. Check from your computer:

```sh
tools/adb-closed.py 192.168.1.50
```

Only "refused the connection" proves the port is closed. A timeout or "No route to host" proves
nothing (on macOS, Local Network privacy causes it too, and `nc` is affected the same way).

If the TV's network comes through a USB Ethernet adapter, unplug it and plug it back in once
debugging is off, then confirm the TV is on Ethernet again and not Wi-Fi.

## Files

| Path | What it does |
| --- | --- |
| `tools/tv.py` | ADB client: shell commands, key presses, screenshots, push and pull |
| `tools/backup.sh` | Saves packages, settings, getprop and display state |
| `tools/classify.py` | Builds the debloat plan; `--explain` inspects a package safely |
| `tools/disable.sh` | Disables the plan in batches with screenshots |
| `tools/check-reenabled.sh` | Lists packages the firmware re-enabled at boot |
| `tools/restore.sh` | Re-enables everything (or named packages) and restores Home |
| `tools/private-dns.sh` | Ad-blocking Private DNS on or off |
| `tools/install-launcher.sh` | Builds, installs and sets the launcher as Home |
| `tools/adb-closed.py` | Confirms network ADB is closed |
| `tools/relay.py` | Forwards ADB through your terminal when a sandbox can't reach the TV |
| `launcher/` | The launcher (Java, AndroidX RecyclerView, minSdk 26) |
| `docs/TROUBLESHOOTING.md` | Known problems and what to do |

`backup/`, `state/`, `shots/` and `plan/` hold data from your TV and are not committed.
