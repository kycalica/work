#!/usr/bin/env python3
"""Talk to the TV over ADB.

Examples:
  tools/tv.py 'getprop ro.build.version.sdk'        run a shell command
  tools/tv.py --keys DPAD_RIGHT DPAD_DOWN           press remote keys
  tools/tv.py --screenshot shots/01.png DPAD_RIGHT  press keys, then capture
  tools/tv.py --push local.apk /data/local/tmp/x.apk
  tools/tv.py --pull /sdcard/x.png x.png

The TV address comes from TV_HOST (required) and TV_PORT (default 5555).
The stock `adb` binary is used when it is on PATH. Set TV_CLIENT=python to use
the `adb-shell` package instead (pip install 'adb-shell[cryptography]'), which
helps when macOS Local Network privacy blocks adb with "No route to host".
"""
import argparse
import base64
import hashlib
import os
import shutil
import subprocess
import sys
import time


def target():
    host = os.environ.get("TV_HOST")
    if not host:
        sys.exit("Set TV_HOST to the TV's IP address, e.g. export TV_HOST=192.168.1.50")
    return host, int(os.environ.get("TV_PORT", "5555"))


class AdbBinary:
    def __init__(self, host, port):
        self.serial = f"{host}:{port}"
        out = subprocess.run(["adb", "connect", self.serial], capture_output=True, text=True)
        text = (out.stdout + out.stderr).strip()
        if "connected" not in text:
            sys.exit(f"adb connect failed: {text}\n"
                     "If the TV shows 'Allow USB debugging?', accept it and run again.")

    def shell(self, cmd):
        out = subprocess.run(["adb", "-s", self.serial, "shell", cmd],
                             capture_output=True, text=True)
        return out.stdout + out.stderr

    def push(self, local, remote):
        subprocess.run(["adb", "-s", self.serial, "push", local, remote], check=True,
                       stdout=subprocess.DEVNULL)

    def pull(self, remote, local):
        subprocess.run(["adb", "-s", self.serial, "pull", remote, local], check=True,
                       stdout=subprocess.DEVNULL)


class AdbPython:
    def __init__(self, host, port):
        try:
            from adb_shell.adb_device import AdbDeviceTcp
            from adb_shell.auth.keygen import keygen
            from adb_shell.auth.sign_pythonrsa import PythonRSASigner
        except ImportError:
            sys.exit("TV_CLIENT=python needs: pip install 'adb-shell[cryptography]'")
        key = os.path.expanduser("~/.android/adbkey")
        if not os.path.exists(key):
            os.makedirs(os.path.dirname(key), exist_ok=True)
            keygen(key)
        with open(key) as f:
            priv = f.read()
        with open(key + ".pub") as f:
            pub = f.read()
        self.dev = AdbDeviceTcp(host, port, default_transport_timeout_s=30.0)
        print("If the TV shows 'Allow USB debugging?', accept it now.", file=sys.stderr)
        self.dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=60.0)

    def shell(self, cmd):
        return self.dev.shell(cmd, timeout_s=120)

    def push(self, local, remote):
        # The adb-shell push can hang on large files: send base64 chunks
        # through the shell instead and compare md5 at the end.
        with open(local, "rb") as f:
            data = f.read()
        self.shell(f"rm -f '{remote}.b64'")
        enc = base64.b64encode(data).decode()
        for i in range(0, len(enc), 60000):
            self.shell(f"echo -n '{enc[i:i + 60000]}' >> '{remote}.b64'")
        self.shell(f"base64 -d '{remote}.b64' > '{remote}' && rm '{remote}.b64'")
        remote_md5 = self.shell(f"md5sum '{remote}'").split()[0]
        if remote_md5 != hashlib.md5(data).hexdigest():
            sys.exit(f"push of {local} failed: md5 mismatch")

    def pull(self, remote, local):
        self.dev.pull(remote, local)


def client():
    host, port = target()
    if os.environ.get("TV_CLIENT") == "python" or not shutil.which("adb"):
        return AdbPython(host, port)
    return AdbBinary(host, port)


def press(tv, keys):
    for key in keys:
        code = key if key.isdigit() or key.startswith("KEYCODE_") else "KEYCODE_" + key
        tv.shell(f"input keyevent {code}")
        time.sleep(0.4)


def screenshot(tv, path):
    time.sleep(0.8)  # let animations settle
    remote = "/data/local/tmp/tv-shot.png"
    tv.shell(f"screencap -p {remote}")
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tv.pull(remote, path)
    tv.shell(f"rm -f {remote}")
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        sys.exit("Empty screenshot. Live TV, HDMI and some apps (Netflix) block capture; "
                 "check `dumpsys activity activities | grep topResumedActivity` instead.")
    print(path)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--keys", nargs="+", metavar="KEY", help="press these keys")
    p.add_argument("--screenshot", metavar="PNG", help="press KEYS (if any), then capture to PNG")
    p.add_argument("--push", nargs=2, metavar=("LOCAL", "REMOTE"))
    p.add_argument("--pull", nargs=2, metavar=("REMOTE", "LOCAL"))
    p.add_argument("args", nargs="*", help="shell command, or keys with --screenshot")
    a = p.parse_args()

    tv = client()
    if a.push:
        tv.push(*a.push)
    elif a.pull:
        tv.pull(*a.pull)
    elif a.keys:
        press(tv, a.keys)
    elif a.screenshot:
        press(tv, a.args)
        screenshot(tv, a.screenshot)
    elif a.args:
        sys.stdout.write(tv.shell(" ".join(a.args)))
    else:
        p.print_help()


if __name__ == "__main__":
    main()
