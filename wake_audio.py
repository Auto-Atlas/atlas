"""Render the 5 AM wake in EVE's REAL voice (Kokoro `af_heart`) to a WAV the phone plays
LOCALLY at wake time — no voice connection, no mic, no echo, works from deep Doze.

Multi-tenant by design: the cache is keyed by a content hash of (voice, text), so each
tenant's whys render to their own file and are re-rendered only when the text changes.
At scale this same function sits behind a render queue / object store; the interface
(get_wake_wav -> bytes + etag) stays identical.
"""

from __future__ import annotations

import hashlib
import io
import os
import wave
from pathlib import Path

from loguru import logger

_DEFAULT_VOICE = os.getenv("KOKORO_VOICE", "af_heart")
_CACHE_DIR = Path(os.getenv("EVE_WAKE_AUDIO_CACHE", str(Path.home() / "eve-wake-audio")))
_engine = None  # one Kokoro ONNX session, reused


def _kokoro():
    global _engine
    if _engine is None:
        from pipecat.services.kokoro.tts import (
            KOKORO_CACHE_DIR,
            Kokoro,
            _ensure_model_files,
        )

        mp = KOKORO_CACHE_DIR / "kokoro-v1.0.onnx"
        vp = KOKORO_CACHE_DIR / "voices-v1.0.bin"
        _ensure_model_files(mp, vp)  # no-op if already downloaded (the voice loops use it)
        _engine = Kokoro(str(mp), str(vp))
    return _engine


def etag(text: str, voice: str | None = None) -> str:
    """Stable short id for a (voice, text) pair — the app uses it to skip re-downloading
    unchanged audio."""
    voice = voice or _DEFAULT_VOICE
    return hashlib.sha256(f"{voice}\x00{text or ''}".encode("utf-8")).hexdigest()[:16]


def _render_wav(text: str, voice: str) -> bytes:
    import numpy as np

    samples, sr = _kokoro().create(text, voice=voice, lang="en-us", speed=1.0)
    pcm = (np.clip(np.asarray(samples, dtype="float32"), -1.0, 1.0) * 32767).astype("int16")
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())
    return buf.getvalue()


def get_wake_wav(text: str, voice: str | None = None) -> tuple[bytes, str]:
    """Return (wav_bytes, etag). Renders once per (voice, text); cached on disk thereafter."""
    voice = voice or _DEFAULT_VOICE
    tag = etag(text, voice)
    try:
        _CACHE_DIR.mkdir(parents=True, exist_ok=True)
        f = _CACHE_DIR / f"wake-{tag}.wav"
        if f.is_file():
            return f.read_bytes(), tag
        wav = _render_wav(text, voice)
        f.write_bytes(wav)
        return wav, tag
    except Exception as e:
        logger.warning(f"wake audio: cache path failed ({e}); rendering uncached")
        return _render_wav(text, voice), tag
