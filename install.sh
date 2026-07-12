#!/usr/bin/env bash
# Atlas one-line installer (Linux / macOS).
#   curl -fsSL https://raw.githubusercontent.com/Auto-Atlas/atlas/main/install.sh | bash
# Idempotent: re-running updates the checkout and dependencies. Overrides:
#   ATLAS_DIR   where to install (default: ~/atlas)
#   ATLAS_REPO  git URL (default: the Auto-Atlas/atlas repo)
set -euo pipefail

fail() { printf '\nATLAS INSTALL FAILED: %s\n' "$1" >&2; exit 1; }
step() { printf '==> %s\n' "$1"; }

REPO="${ATLAS_REPO:-https://github.com/Auto-Atlas/atlas.git}"
DIR="${ATLAS_DIR:-$HOME/atlas}"
OS="$(uname -s)"

# ---- prerequisites: fail loudly with the exact fix, never limp on ----------
command -v git >/dev/null 2>&1 || fail "git is not installed. Install it (apt/dnf/brew install git), then re-run."
PY=""
for cand in python3.11 python3; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" --version 2>&1 | grep -q 'Python 3\.11\.'; then PY="$cand"; break; fi
done
[ -n "$PY" ] || fail "Python 3.11 is required (3.12+/3.10- are not the tested stack). Linux: apt install python3.11 python3.11-venv python3.11-dev (deadsnakes PPA on Ubuntu). macOS: brew install python@3.11. Then re-run."

if [ "$OS" = "Linux" ]; then
  # On Linux, requirements.txt builds pyaudio from source (no wheel), which
  # needs a C compiler, the portaudio headers, and the Python dev headers.
  # Check ALL of it up front and fail with one exact command — never let the
  # user discover this 10 minutes into pip.
  MISSING=""
  command -v cc >/dev/null 2>&1 || command -v gcc >/dev/null 2>&1 || MISSING="$MISSING build-essential"
  { command -v pkg-config >/dev/null 2>&1 && pkg-config --exists portaudio-2.0 2>/dev/null; } || MISSING="$MISSING portaudio19-dev pkg-config"
  PYINC="$("$PY" -c 'import sysconfig; print(sysconfig.get_path("include"))' 2>/dev/null)"
  [ -f "$PYINC/Python.h" ] || MISSING="$MISSING ${PY##*/}-dev"
  [ -z "$MISSING" ] || fail "missing build prerequisites for the audio stack. Run: sudo apt install -y$MISSING   (or your distro's equivalent), then re-run."
fi

# ---- get the code -----------------------------------------------------------
if [ -d "$DIR/.git" ]; then
  step "Updating existing checkout at $DIR"
  git -C "$DIR" pull --ff-only || fail "git pull failed in $DIR (local changes?). Resolve and re-run."
else
  step "Cloning $REPO -> $DIR"
  git clone "$REPO" "$DIR" || fail "git clone failed."
fi

# ---- python env -------------------------------------------------------------
if [ ! -x "$DIR/.venv/bin/python" ]; then
  step "Creating virtualenv (.venv)"
  "$PY" -m venv "$DIR/.venv" || fail "venv creation failed (python3.11-venv installed?)."
fi
step "Installing dependencies (this downloads the AI stack - several GB on first run)"
"$DIR/.venv/bin/python" -m pip install --upgrade pip --quiet
"$DIR/.venv/bin/python" -m pip install -r "$DIR/requirements.txt" || fail "pip install failed - scroll up for the real error."

# ---- config -----------------------------------------------------------------
if [ ! -f "$DIR/.env" ]; then
  step "Creating .env from .env.example"
  cp "$DIR/.env.example" "$DIR/.env"
fi
if [ "$OS" = "Darwin" ] || ! command -v nvidia-smi >/dev/null 2>&1; then
  step "No NVIDIA GPU here - switching Whisper to CPU mode in .env"
  sed -i.bak -e 's/WHISPER_DEVICE=cuda/WHISPER_DEVICE=cpu/' -e 's/WHISPER_COMPUTE=float16/WHISPER_COMPUTE=int8/' "$DIR/.env" && rm -f "$DIR/.env.bak"
fi

# ---- ollama (the local brain) ----------------------------------------------
command -v ollama >/dev/null 2>&1 || fail "Ollama is required for local mode. Install from https://ollama.com/download, then re-run this installer (it will pull the model and finish)."
step "Pulling the voice model (qwen3:8b - no-op if you already have it)"
ollama pull qwen3:8b || fail "ollama pull failed - is the Ollama service running?"

printf '\nAtlas is installed.\n'
printf '  Start it:   cd %s && ./run.sh\n' "$DIR"
printf '  It greets you out loud (~20s to load), then just talk.\n'
printf '  Full guide: %s/SETUP-GUIDE.md (phone/SMS extras, troubleshooting)\n' "$DIR"
