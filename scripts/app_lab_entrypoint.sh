#!/usr/bin/env sh
set -eu

NPU_DEVICE="${NPU_DEVICE:-/dev/fastrpc-adsp}"
APP_UID="${APP_UID:-1000}"
APP_GID="${APP_GID:-1000}"
APP_GROUPS="${APP_GROUPS:-20,29,44,991,1001,1000}"

if [ -e "$NPU_DEVICE" ]; then
  echo "Opening NPU device permissions: $NPU_DEVICE"
  chmod 666 "$NPU_DEVICE" || {
    echo "Failed to chmod $NPU_DEVICE; continuing so app can report runtime status." >&2
    ls -l "$NPU_DEVICE" >&2 || true
  }
else
  echo "NPU device not found: $NPU_DEVICE" >&2
fi

if [ "$(id -u)" = "0" ]; then
  exec setpriv \
    --reuid "$APP_UID" \
    --regid "$APP_GID" \
    --groups "$APP_GROUPS" \
    /run.sh "$@"
fi

exec /run.sh "$@"
