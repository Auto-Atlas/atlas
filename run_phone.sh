#!/usr/bin/env bash
# Launch the Jarvis/EVE phone WebRTC voice loop on Linux (port 8788).
# Same venv and GPU library setup as run.sh; runs NEXT TO bot.py.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -x ".venv/bin/python" ]; then
  echo "[!] No .venv found. Run setup first — see README.md" >&2
  exit 1
fi

NVIDIA_LIBS="$(find "$PWD/.venv/lib"/python*/site-packages/nvidia -maxdepth 2 -type d -name lib 2>/dev/null | paste -sd:)"
if [ -n "$NVIDIA_LIBS" ]; then
  export LD_LIBRARY_PATH="$NVIDIA_LIBS${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

exec .venv/bin/python phone_bot.py
