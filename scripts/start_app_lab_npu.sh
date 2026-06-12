#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$APP_DIR/.cache/app-compose.yaml"
OVERRIDE_FILE="$APP_DIR/.cache/app-compose-overrides.yaml"
NPU_DEVICE="/dev/fastrpc-adsp"

echo "[1/6] Checking NPU device..."
if [ ! -e "$NPU_DEVICE" ]; then
  echo "Missing $NPU_DEVICE" >&2
  exit 1
fi
ls -l "$NPU_DEVICE"

echo "[2/6] Restarting Arduino App Lab app..."
arduino-app-cli app restart "$APP_DIR"

echo "[3/6] Opening NPU device permissions after App Lab restart..."
if [ ! -r "$NPU_DEVICE" ] || [ ! -w "$NPU_DEVICE" ]; then
  sudo -n chmod 666 "$NPU_DEVICE" || echo "Host chmod skipped; will open permissions inside the App Lab container."
else
  echo "NPU device is already readable/writable."
fi
ls -l "$NPU_DEVICE"

echo "[4/6] Adding NPU startup entrypoint and cgroup rule to App Lab compose override..."
python3 - "$OVERRIDE_FILE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])

block = """  main:
    user: 0:0
    entrypoint: /app/scripts/app_lab_entrypoint.sh
    device_cgroup_rules:
    - c 10:* rmw
    - c 237:* rmw
    environment:
      APP_GROUPS: 20,29,44,991,1001,1000
      APP_GID: "1000"
      APP_UID: "1000"
      CAMERA_DEVICE: auto
      NPU_DEVICE: /dev/fastrpc-adsp
      ONNX_REQUIRE_QNN_ONLY: "0"
      WEB_PORT: "5001"
      YOLO_BACKEND: litert_cpu
      YOLO_MODEL: data/input/best_int8.tflite
"""

text = path.read_text(encoding="utf-8") if path.exists() else "services:\n"
if not text.strip():
    text = "services:\n"

if not text.startswith("services:\n"):
    raise SystemExit(f"Unexpected compose override format: {path}")

lines = text.splitlines()
out = []
i = 0
inserted = False

while i < len(lines):
    if lines[i] == "  main:":
        out.extend(block.rstrip("\n").splitlines())
        inserted = True
        i += 1
        while i < len(lines) and not lines[i].startswith("  "):
            i += 1
        while i < len(lines) and (lines[i].startswith("    ") or lines[i].strip() == ""):
            i += 1
        continue
    out.append(lines[i])
    i += 1

if not inserted:
    out = ["services:"] + block.rstrip("\n").splitlines() + lines[1:]

text = "\n".join(out) + "\n"
path.write_text(text, encoding="utf-8")
print("Ensured NPU cgroup rule for main container.")
PY

echo "[5/6] Recreating App Lab containers with NPU access..."
docker compose -f "$COMPOSE_FILE" -f "$OVERRIDE_FILE" up -d --force-recreate

echo "Opening NPU device permissions inside App Lab container..."
docker exec -u root roadchell-main-1 chmod 666 "$NPU_DEVICE" || true
docker exec roadchell-main-1 ls -l "$NPU_DEVICE" || true

echo "[6/6] Checking app status..."
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS http://localhost:5001/status; then
    echo
    exit 0
  fi
  sleep 1
done

echo "App started, but /status did not respond yet. Check with: arduino-app-cli monitor" >&2
