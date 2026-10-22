#!/usr/bin/env python3
"""Sort the TV's packages into debloat categories and write plan/debloat-plan.txt.

Usage:
  tools/classify.py                 classify the newest backup, ask about streaming/voice/cast/updates
  tools/classify.py --no-ask        same, but keep every app you would have been asked about
  tools/classify.py --explain PKG   show a package's activities and services (never launches it)

The categories come from keywords in package names, so they are suggestions,
not facts. Read the plan before running tools/disable.sh: a manufacturer
package name often doesn't say what it does. Use --explain on anything unclear.
"""
import argparse
import glob
import os
import re
import subprocess
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(TOOLS)


def regex_from_lib(name):
    with open(os.path.join(TOOLS, "lib.sh")) as f:
        m = re.search(rf"^{name}='(.*)'$", f.read(), re.M)
    return re.compile(m.group(1))


FACTORY = regex_from_lib("FACTORY_RE")
PROTECTED = regex_from_lib("PROTECTED_RE")

# Order matters: the first match wins. Checked after FACTORY and PROTECTED.
CATEGORIES = [
    ("ask", "Voice assistant, Chromecast, Miracast, firmware updates",
     re.compile(r"katniss|assistant|voice|mediashell|chromecast|\.cast|miracast|screenmirror|mirror|"
                r"wfd|dlna|airplay|\bota\b|\.ota|fota|update|upgrade")),
    ("streaming", "Streaming apps",
     re.compile(r"netflix|youtube|amazon.*(video|livingroom)|primevideo|disney|hulu|spotify|plex|"
                r"pluto|hbo|wbd|paramount|peacock|tubi|twitch|vudu|appletv|crunchyroll|sling|"
                r"fubo|dazn|deezer|tidal|iqiyi|viu|zee5|hotstar|rakuten|google\.android\.videos")),
    ("telemetry", "Manufacturer telemetry and analytics",
     re.compile(r"analytic|statistic|telemetry|tracker|tracking|logcollect|logupload|bugreport|"
                r"feedback|crashreport|usagestat|bigdata|userexperience|dataservice|\.dcs|collector")),
    ("content", "Manufacturer content and ad services",
     re.compile(r"advert|\.ads?\b|adservice|recommend|patchwall|promot|promo|banner|ottcontent|"
                r"homescreen|contentprovider|discover|operation|appstore|appmarket|market\.")),
    ("background", "Background services you probably don't need",
     re.compile(r"cleaner|clean|boost|optimiz|\.share|transfer|filemanager|screensaver|weather|"
                r"demo|retail|storemode|shopmode|usercenter|account\.(tcl|hisense|mi)")),
    ("preloaded", "Preloaded apps",
     re.compile(r"game|music|browser|gallery|photo|kids|karaoke|fitness|news|shop|mall|"
                r"education|health|calendar|clock|calculator")),
]
TITLES = {key: title for key, title, _ in CATEGORIES}
DISABLE_BY_DEFAULT = {"telemetry", "content", "background"}


def newest_backup():
    found = sorted(glob.glob(os.path.join(REPO, "backup", "*", "packages-all.txt")))
    if not found:
        sys.exit("No backup found. Run tools/backup.sh first.")
    return os.path.dirname(found[-1])


def read_packages(path):
    pkgs = []
    if not os.path.exists(path):
        return pkgs
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line.startswith("package:"):
                pkgs.append(line.rsplit("=", 1)[-1] if "=" in line else line[8:])
    return pkgs


def classify(pkg, third_party):
    if FACTORY.search(pkg):
        return "factory"
    if PROTECTED.search(pkg):
        return "protected"
    for key, _, rx in CATEGORIES:
        if rx.search(pkg):
            return key
    if pkg in third_party:
        return "preloaded"
    if pkg.startswith(("com.android.", "com.google.", "android.")):
        return "leave"
    return "review"


def ask(question):
    try:
        answer = input(f"{question} [Y/n] ").strip().lower()
    except EOFError:
        return True
    return answer in ("", "y", "yes")


def explain(pkg):
    out = subprocess.run([sys.executable, os.path.join(TOOLS, "tv.py"), f"dumpsys package {pkg}"],
                         capture_output=True, text=True).stdout
    keep = re.compile(r"^\s+(?:[0-9a-f]+ )?" + re.escape(pkg) + r"/\S+|Activity Resolver|Service Resolver|"
                      r"Receiver Resolver|versionName|codePath|flags=")
    lines = [l for l in out.splitlines() if keep.search(l)]
    print("\n".join(dict.fromkeys(lines)) or out[:3000])


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--no-ask", action="store_true")
    p.add_argument("--explain", metavar="PKG")
    p.add_argument("--backup", help="backup folder (default: newest)")
    a = p.parse_args()

    if a.explain:
        if FACTORY.search(a.explain):
            print("Factory/production package: only dumpsys is used, it is never started.")
        explain(a.explain)
        return

    folder = a.backup or newest_backup()
    pkgs = read_packages(os.path.join(folder, "packages-all.txt"))
    third_party = set(read_packages(os.path.join(folder, "packages-thirdparty.txt")))
    already = set(read_packages(os.path.join(folder, "packages-disabled.txt")))

    groups = {}
    for pkg in sorted(pkgs):
        groups.setdefault(classify(pkg, third_party), []).append(pkg)

    keep_asked = set()
    interactive = sys.stdin.isatty() and not a.no_ask
    for key in ("streaming", "ask"):
        items = [x for x in groups.get(key, []) if x not in already]
        if not items:
            continue
        if interactive:
            print(f"\n{TITLES[key]}: answer Y for each one you use (it stays enabled).")
        for pkg in items:
            if not interactive or ask(f"  Do you use {pkg}?"):
                keep_asked.add(pkg)

    os.makedirs(os.path.join(REPO, "plan"), exist_ok=True)
    plan = os.path.join(REPO, "plan", "debloat-plan.txt")
    with open(plan, "w") as f:
        f.write("# Debloat plan. One package per line; lines starting with # are skipped.\n"
                "# Generated from package names only: check each line before running tools/disable.sh.\n"
                "# tools/classify.py --explain <pkg> shows what a package contains without starting it.\n"
                f"# Source: {os.path.relpath(folder, REPO)}\n")
        for key, title, _ in CATEGORIES + [("review", "Unknown manufacturer packages: check with --explain, "
                                                      "uncomment only if you are sure", None)]:
            items = [x for x in groups.get(key, []) if x not in already]
            if not items:
                continue
            f.write(f"\n## {title}\n")
            for pkg in items:
                on = key in DISABLE_BY_DEFAULT or (key in ("streaming", "ask") and pkg not in keep_asked)
                if key in ("streaming", "ask") and not interactive:
                    on = False
                f.write(f"{'' if on else '# '}{pkg}\n")
        for key, title in (("factory", "Factory/production packages: NEVER launch or disable"),
                           ("protected", "Needed by the TV: never disabled")):
            if groups.get(key):
                f.write(f"\n## {title}\n" + "".join(f"#! {x}\n" for x in groups[key]))

    counts = ", ".join(f"{k}: {len(v)}" for k, v in sorted(groups.items()))
    print(f"\nWrote {os.path.relpath(plan, REPO)} ({counts}).")
    print("Review it, then run tools/disable.sh plan/debloat-plan.txt")


if __name__ == "__main__":
    main()
