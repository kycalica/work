# shellcheck shell=bash disable=SC2034
# Shared helpers for the shell tools. Source it, don't run it.
TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$TOOLS_DIR")"
STATE_DIR="$REPO_DIR/state"
DISABLED_LOG="$STATE_DIR/disabled.txt"
mkdir -p "$STATE_DIR"

: "${TV_HOST:?Set TV_HOST to the TV IP address, e.g. export TV_HOST=192.168.1.50}"

# Run one shell command on the TV. Output has carriage returns stripped.
tv() { python3 "$TOOLS_DIR/tv.py" "$*" | tr -d '\r'; }

shot() { python3 "$TOOLS_DIR/tv.py" --screenshot "$@"; }

# Packages that must never be launched, not even to look at them: on
# Haier/CVTE boards opening one flips a factory-mode flag that survives reboots.
FACTORY_RE='(factory|fac\.menu|hkcfac|\.fac$|\.fac\.|\.at\.factory|production|factorymenu)'

# Packages the TV needs to work. disable.sh refuses them.
PROTECTED_RE='(^android$|systemui|^com\.android\.(settings|providers\.|packageinstaller|shell|phone|bluetooth|inputdevices|externalstorage|keychain|location)|tv\.settings|tvsettings|setupwraith|inputmethod|keyboard|leanbackime|\.ime$|bluetooth|remote|rcu|tvinput|tv\.input|inputsource|\.source|tuner|hdmi|\.cec|livetv|tvplayer|tv\.app$|tvcenter|^com\.google\.android\.gms|^com\.google\.android\.gsf|^com\.android\.vending$|permissioncontroller|^com\.android\.server|webview|networkstack|wifi|connectivity|audio|media\.tv|tvprovider|launcher)'
