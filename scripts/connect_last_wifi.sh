#!/usr/bin/env bash
set -euo pipefail

# Connect to the most recently used saved Wi-Fi profile in NetworkManager.
# This does not create new credentials; it only reuses profiles that already
# exist on the device.

WIFI_IFACE="${LAST_WIFI_IFACE:-${HY_WIFI_IFACE:-}}"
FORCE_SWITCH="${LAST_WIFI_FORCE:-0}"

if [ -z "$WIFI_IFACE" ]; then
  WIFI_IFACE="$(nmcli -t -f DEVICE,TYPE device status | awk -F: '$2 == "wifi" { print $1; exit }')"
fi

if [ -z "$WIFI_IFACE" ]; then
  echo "No Wi-Fi interface found. Skipping Wi-Fi reconnect." >&2
  exit 0
fi

nmcli radio wifi on

ACTIVE_CONNECTION="$(
  nmcli -t -f DEVICE,TYPE,STATE,CONNECTION device status |
    awk -F: -v iface="$WIFI_IFACE" '$1 == iface && $2 == "wifi" && $3 == "connected" { print $4; exit }'
)"

if [ -n "$ACTIVE_CONNECTION" ] && [ "$FORCE_SWITCH" != "1" ]; then
  echo "Wi-Fi already connected on $WIFI_IFACE: $ACTIVE_CONNECTION"
  exit 0
fi

LAST_CONNECTION="$(
  nmcli -t -f NAME,TYPE,TIMESTAMP connection show |
    awk -F: '$2 == "802-11-wireless" && $3 ~ /^[0-9]+$/ && $3 > 0 { print $3 "\t" $1 }' |
    sort -rn |
    awk -F '\t' 'NR == 1 { print $2 }'
)"

if [ -z "$LAST_CONNECTION" ]; then
  echo "No previously used Wi-Fi profile found. Skipping Wi-Fi reconnect." >&2
  exit 0
fi

echo "Connecting to last used Wi-Fi profile: $LAST_CONNECTION"
nmcli connection up "$LAST_CONNECTION" ifname "$WIFI_IFACE"
