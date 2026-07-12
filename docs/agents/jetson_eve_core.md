# EVE Core on the Jetson body — deploy & run

EVE's third body, beside `bot.py` (desktop) and `phone_bot.py` (phone). Reuses
`jarvis_core` verbatim (persona/tools/memory) so the bodies can't drift. Riva
STT/TTS over local mic+speaker, plus OAK-D `look` and RUKA `actuate_hand` tools.

Spec: `docs/superpowers/specs/2026-06-28-jetson-eve-core-design.md`
Plan: `docs/superpowers/plans/2026-06-28-jetson-eve-core.md`

## Topology (what runs where)

```
The Jetson body (Jetson Orin, JetPack 6.2 / L4T R36.4.7, CUDA 12.6)
  ├─ Python-stack container  ──gRPC :50051──►  riva-speech:2.19.0-l4t container
  │    runs jetson_bot.py                         (ASR conformer + TTS fastpitch)
  │    pipecat 1.3.0 + riva.client                NEVER rebuilt; EVE never enters it
  │    LocalAudioTransport (mic+speaker)
  │    talks to the local llama.cpp LLM endpoint (voice_llm)
  └─ ruka_hand conda env  ◄──conda run subprocess──  hand_tool.py (actuate_hand)
```

**Both containers are sacred** — do not rebuild either. EVE adds nothing to the
Riva server; it only speaks gRPC to it. EVE runs inside the existing Python-stack
container; we do not modify its env.

## Env defaults on the Jetson

```bash
export JARVIS_STT=riva                 # Riva streaming ASR (NVIDIA-optimized ears)
export JARVIS_TTS=riva                 # milestone FLOOR: self-contained, no extra server
# export JARVIS_TTS=chatterbox         # persona UPGRADE once the loop is proven + the
                                       #   Chatterbox-Turbo server (:8004) is confirmed up [V5]
export RIVA_SERVER=localhost:50051
export RIVA_ASR_MODEL=conformer-en-US-asr-streaming-asr-bls-ensemble
export RIVA_ASR_RATE=16000
export RIVA_TTS_RATE=22050
# export RIVA_TTS_VOICE=               # blank => Riva default voice

# Audio devices (pyaudio/PortAudio indices; blank => system default)
# export JARVIS_AUDIO_IN_DEVICE=1
# export JARVIS_AUDIO_OUT_DEVICE=1
export JARVIS_AUDIO_IN_RATE=16000      # matches Riva ASR in
export JARVIS_AUDIO_OUT_RATE=24000     # transport out; TTS engine resamples as needed
export JARVIS_PHONE_HALF_DUPLEX=1      # single-box speaker: don't let EVE hear herself

# Robotic hand (RUKA) — Jetson only
export RUKA_CONDA_ENV=ruka_hand
export RUKA_POSE_SCRIPT=/mnt/ssd/models/RUKA/eve_pose.py   # copy deploy/ruka/eve_pose.py here
export RUKA_TIMEOUT_S=20

# Single-user owner tier (no enrolled voiceprint on the robot yet)
export EVE_UNSAFE_TREAT_ALL_AS_OWNER=1
```

## Run

```bash
# 1. Bring Riva up (in its own container — leaves it running)
cd /mnt/ssd/riva_quickstart_arm64_v2.19.0 && bash riva_start.sh

# 2. From inside the Python-stack container, with the env above:
python jetson_bot.py
```

## Pre-flight gates (verify on the Jetson before declaring the live path green)

| Gate | Check |
|---|---|
| **V1** Riva up + models | `riva_start.sh`; gRPC `:50051` listening; model repo serves `conformer-en-US-asr-streaming-asr-bls-ensemble` + `fastpitch_hifigan_ensemble-English-US` |
| **V2** riva.client | `python -c "import riva.client; print('ok')"` in the runtime |
| **V3** container co-residency | `python -c "import pipecat, riva.client"` **inside the Python-stack container** — if it fails, do NOT rebuild; file the portability issue and ask |
| **V4** audio devices | mic + speaker enumerate under `LocalAudioTransport` (pyaudio) in the container |
| **V5** Chatterbox server | `:8004` up **or** stay on `JARVIS_TTS=riva` |
| **V6** RUKA path | `conda run -n ruka_hand python eve_pose.py --pose reset --hand right` runs; `/dev/ttyUSB0/1` present; user in `dialout`; confirm `Controller` API in `ruka_hand/control/controller.py` |
| **V7** sample rates | live server rates match the hardcoded 16000 (ASR) / 22050 (TTS) |
| **V8** depthai | `python -c "import depthai"` loads on JetPack 6.2; OAK-D enumerates |
| **V9** LLM endpoint | the local llama.cpp endpoint `voice_llm` targets is up + reachable ("LLM responds" is half the bar) |
| **V10** Riva version | re-confirm 2.24 deprecates Orin → stay pinned at 2.19 (conservative outcome holds either way) |

## Dev-box vs the Jetson

Dev-box green (`.venv/bin/python -m pytest tests/`, incl. `test_import_boundary.py`)
proves **import-clean, structurally-correct code** — backends, the env switch, the
body's tool-call + aggregator wiring, the OAK-D/hand guards. It is NOT a working
robot. V1–V10 are what only the Jetson confirms.

## Portability conflicts → GitHub issues

Filed on `<your-org>/jarvis-sidecar` (see close-out):
1. `nvidia-riva-client` x86 vs ARM64 availability/pinning (Jetson-only extra).
2. `depthai` wheel/build on JetPack 6.2 (ARM64-only).
3. RUKA's separate conda env + serial → EVE bridges via subprocess, not import.
4. (If V3 fails) Python-stack container lacks pipecat/riva.client under the no-rebuild rule.
