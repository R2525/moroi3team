#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="$APP_DIR/venv/bin/python3"

export WEB_PORT="${WEB_PORT:-5002}"
export YOLO_MODEL="${YOLO_MODEL:-data/input/best_int8.tflite}"
export YOLO_BACKEND="${YOLO_BACKEND:-litert_cpu}"
export ONNX_REQUIRE_QNN_ONLY="${ONNX_REQUIRE_QNN_ONLY:-0}"

if [ ! -x "$PYTHON_BIN" ]; then
  echo "Missing local venv Python: $PYTHON_BIN" >&2
  echo "Create it with: python3 -m venv venv && venv/bin/python3 -m pip install -r python/requirements.txt" >&2
  exit 1
fi

exec "$PYTHON_BIN" "$APP_DIR/python/main.py"
