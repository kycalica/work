#!/usr/bin/env bash
# Optional ad-blocking DNS through Android's Private DNS (Android 9 and newer).
# It blocks many ad and tracking domains but NOT YouTube ads.
# Usage: TV_HOST=<ip> tools/private-dns.sh on|off|status
set -uo pipefail
source "$(dirname "$0")/lib.sh"

case "${1:-status}" in
  on)
    sdk=$(tv 'getprop ro.build.version.sdk')
    [ "${sdk:-0}" -ge 28 ] || { echo "Private DNS needs Android 9 (SDK 28); this TV is SDK $sdk."; exit 1; }
    tv 'settings put global private_dns_specifier dns.adguard-dns.com'
    tv 'settings put global private_dns_mode hostname'
    echo "Private DNS set to dns.adguard-dns.com. Open a streaming app and check it still plays." ;;
  off)
    tv 'settings put global private_dns_mode opportunistic'
    echo "Private DNS back to automatic." ;;
  status)
    echo "mode: $(tv 'settings get global private_dns_mode')"
    echo "host: $(tv 'settings get global private_dns_specifier')" ;;
  *) echo "Usage: tools/private-dns.sh on|off|status"; exit 1 ;;
esac
