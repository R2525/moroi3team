#!/usr/bin/env bash
set -euo pipefail

SSID="${HY_WIFI_SSID:-HY-WiFi}"
CONNECTION_NAME="${HY_WIFI_CONNECTION:-HY-Wifi}"
IDENTITY="${HY_WIFI_ID:-}"
PASSWORD="${HY_WIFI_PASSWORD:-}"
WIFI_IFACE="${HY_WIFI_IFACE:-}"
CA_CERT="${HY_WIFI_CA_CERT:-}"
DOMAIN_SUFFIX_MATCH="${HY_WIFI_DOMAIN_SUFFIX_MATCH:-}"
ANONYMOUS_IDENTITY="${HY_WIFI_ANON_ID:-}"

if [ -f ".env.hy-wifi" ]; then
  # shellcheck disable=SC1091
  . ".env.hy-wifi"
  SSID="${HY_WIFI_SSID:-$SSID}"
  CONNECTION_NAME="${HY_WIFI_CONNECTION:-$CONNECTION_NAME}"
  IDENTITY="${HY_WIFI_ID:-$IDENTITY}"
  PASSWORD="${HY_WIFI_PASSWORD:-$PASSWORD}"
  WIFI_IFACE="${HY_WIFI_IFACE:-$WIFI_IFACE}"
  CA_CERT="${HY_WIFI_CA_CERT:-$CA_CERT}"
  DOMAIN_SUFFIX_MATCH="${HY_WIFI_DOMAIN_SUFFIX_MATCH:-$DOMAIN_SUFFIX_MATCH}"
  ANONYMOUS_IDENTITY="${HY_WIFI_ANON_ID:-$ANONYMOUS_IDENTITY}"
fi

if [ -z "$IDENTITY" ]; then
  read -r -p "HY-Wifi ID: " IDENTITY
fi

if [ -z "$PASSWORD" ]; then
  read -r -s -p "HY-Wifi password: " PASSWORD
  echo
fi

if [ -z "$WIFI_IFACE" ]; then
  WIFI_IFACE="$(nmcli -t -f DEVICE,TYPE device status | awk -F: '$2 == "wifi" { print $1; exit }')"
fi

if [ -z "$WIFI_IFACE" ]; then
  echo "No Wi-Fi interface found. Set HY_WIFI_IFACE=wlan0 if needed." >&2
  exit 1
fi

NMCLI=(nmcli)

echo "Configuring $SSID on $WIFI_IFACE as $IDENTITY"
"${NMCLI[@]}" radio wifi on

if "${NMCLI[@]}" -t -f NAME connection show | grep -Fxq "$CONNECTION_NAME"; then
  echo "Updating existing connection: $CONNECTION_NAME"
else
  echo "Creating connection: $CONNECTION_NAME"
  "${NMCLI[@]}" connection add type wifi ifname "$WIFI_IFACE" con-name "$CONNECTION_NAME" ssid "$SSID"
fi

"${NMCLI[@]}" connection modify "$CONNECTION_NAME" \
  connection.autoconnect yes \
  connection.autoconnect-priority 50 \
  wifi.ssid "$SSID" \
  wifi-sec.key-mgmt wpa-eap \
  802-1x.eap peap \
  802-1x.identity "$IDENTITY" \
  802-1x.password "$PASSWORD" \
  802-1x.password-flags 0 \
  802-1x.phase2-auth mschapv2

if [ -n "$ANONYMOUS_IDENTITY" ]; then
  "${NMCLI[@]}" connection modify "$CONNECTION_NAME" 802-1x.anonymous-identity "$ANONYMOUS_IDENTITY"
fi

if [ -n "$CA_CERT" ]; then
  "${NMCLI[@]}" connection modify "$CONNECTION_NAME" \
    802-1x.system-ca-certs no \
    802-1x.ca-cert "$CA_CERT"
  if [ -n "$DOMAIN_SUFFIX_MATCH" ]; then
    "${NMCLI[@]}" connection modify "$CONNECTION_NAME" 802-1x.domain-suffix-match "$DOMAIN_SUFFIX_MATCH"
  fi
else
  echo "Warning: no CA certificate configured for $SSID. Ask school IT for the CA certificate if login fails or certificate validation is required." >&2
  "${NMCLI[@]}" connection modify "$CONNECTION_NAME" 802-1x.system-ca-certs no
fi

echo "Connecting..."
"${NMCLI[@]}" connection up "$CONNECTION_NAME"
echo "Done. NetworkManager will autoconnect to $SSID on future boots."
