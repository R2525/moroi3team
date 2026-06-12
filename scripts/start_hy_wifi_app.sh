#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"

"$APP_DIR/scripts/connect_hy_wifi.sh"
exec "$APP_DIR/scripts/start_app_lab_npu.sh"
