#!/usr/bin/env bash
# Launch the Jarvis sidecar on Linux.
# Usage:  ./run.sh            (local, free)
#         ./run.sh showtime   (cloud, premium voice)
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -x ".venv/bin/python" ]; then
  echo "[!] No .venv found. Run setup first — see README.md" >&2
  exit 1
fi

# faster-whisper (CTranslate2) dlopens libcudnn/libcublas. The pip nvidia
# wheels ship them inside the venv; LD_LIBRARY_PATH must be set BEFORE the
# process starts (the Linux twin of bot.py's win32 add_dll_directory block).
NVIDIA_LIBS="$(find "$PWD/.venv/lib"/python*/site-packages/nvidia -maxdepth 2 -type d -name lib 2>/dev/null | paste -sd:)"
if [ -n "$NVIDIA_LIBS" ]; then
  export LD_LIBRARY_PATH="$NVIDIA_LIBS${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

if [ -n "${1:-}" ]; then
  export JARVIS_MODE="$1"
fi

exec .venv/bin/python bot.py
