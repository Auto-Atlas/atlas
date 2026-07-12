"""One-shot speech for the watch voice turn: EVE's own ears (faster-whisper) and her own
voice (Chatterbox, the SAME engine+voice the live loop speaks with) as plain functions —
audio bytes in, text out; text in, audio bytes out. No Google, no WebRTC session.

Import invariant (same as approval_api): NO import of jarvis_core / bot / phone_bot /
speaker_state. speech_factory.py must NOT be reused here — it imports jarvis_core. This
module mirrors wake_audio.py's warm-singleton pattern instead.

House rule — no silent fallbacks: there is exactly ONE canonical voice (JARVIS_TTS_VOICE via
Chatterbox). If the voice server is down we raise VoiceUnavailable and the caller ships the
reply as TEXT with a visible voice_error — never a different engine pretending to be EVE.
"""

from __future__ import annotations

import io
import os
import wave

from loguru import logger

# Same env keys the live voice loop reads (speech_factory.py) — one config, one voice.
_STT_MODEL = os.getenv("EVE_API_WHISPER_MODEL", "Systran/faster-distil-whisper-medium.en")
_STT_DEVICE = os.getenv("WHISPER_DEVICE", "cuda")
# int8 by default: this model co-resides with the voice loops' Whisper instances on one GPU.
_STT_COMPUTE = os.getenv("EVE_API_WHISPER_COMPUTE", "int8")
_TTS_BASE = os.getenv("JARVIS_TTS_BASE_URL", "http://127.0.0.1:8004/v1")
_TTS_VOICE = os.getenv("JARVIS_TTS_VOICE", "")

TURN_SAMPLE_RATE = 16_000  # one rate for both directions: watch mic AND watch playback

_whisper = None  # one CTranslate2 model per process, reused (wake_audio._engine pattern)


class VoiceUnavailable(Exception):
    """EVE's voice (Chatterbox) could not render — the reply must ship as text + a visible
    voice_error, never silently switch engines."""


def _model():
    global _whisper
    if _whisper is None:
        from faster_whisper import WhisperModel

        logger.info(f"speech_oneshot: loading {_STT_MODEL} ({_STT_DEVICE}/{_STT_COMPUTE})")
        _whisper = WhisperModel(_STT_MODEL, device=_STT_DEVICE, compute_type=_STT_COMPUTE)
    return _whisper


def warm() -> None:
    """Load the STT model ahead of the first turn (called from approval_api startup on a
    thread). TTS needs no warm-up — Chatterbox is a persistent server."""
    _model()


def transcribe(wav_bytes: bytes, language: str = "en") -> str:
    """WAV bytes -> transcript. Raises ValueError on undecodable audio; returns '' for
    genuine silence (the endpoint maps that to its named 422, never forwards it)."""
    import numpy as np

    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as w:
            rate = w.getframerate()
            channels = w.getnchannels()
            width = w.getsampwidth()
            frames = w.readframes(w.getnframes())
    except Exception as e:
        raise ValueError(f"undecodable WAV: {e}") from e
    if width != 2 or not frames:
        raise ValueError(f"expected 16-bit PCM with data, got width={width} bytes, "
                         f"{len(frames)} payload bytes")

    pcm = np.frombuffer(frames, dtype=np.int16)
    if channels > 1:
        pcm = pcm.reshape(-1, channels).mean(axis=1).astype(np.int16)
    audio = pcm.astype(np.float32) / 32768.0
    if rate != TURN_SAMPLE_RATE:
        # Linear resample is fine for speech into Whisper (it resamples internally anyway;
        # this just keeps its input contract at 16k).
        n = int(len(audio) * TURN_SAMPLE_RATE / rate)
        audio = np.interp(
            np.linspace(0, len(audio), n, endpoint=False), np.arange(len(audio)), audio
        ).astype(np.float32)

    segments, _info = _model().transcribe(audio, language=language, beam_size=1)
    return " ".join(s.text.strip() for s in segments).strip()


def synthesize(text: str) -> bytes:
    """Text -> 16 kHz mono PCM16 WAV in EVE's canonical voice. Raises VoiceUnavailable on
    any voice-leg failure (server down, bad status, unset voice, undecodable render)."""
    if not _TTS_VOICE:
        raise VoiceUnavailable("JARVIS_TTS_VOICE is not set — no canonical voice configured")
    import httpx

    try:
        resp = httpx.post(
            f"{_TTS_BASE}/audio/speech",
            json={
                "input": text,
                "model": "tts-1",
                "voice": _TTS_VOICE,  # server-side voice file, passed verbatim
                "response_format": "wav",
            },
            timeout=20.0,
        )
    except Exception as e:
        raise VoiceUnavailable(f"EVE's voice server unreachable: {e!r}") from e
    if resp.status_code != 200:
        raise VoiceUnavailable(f"EVE's voice server returned {resp.status_code}")
    try:
        return _downsample_wav(resp.content, TURN_SAMPLE_RATE)
    except Exception as e:
        raise VoiceUnavailable(f"voice render undecodable: {e!r}") from e


def _downsample_wav(wav_bytes: bytes, target_rate: int) -> bytes:
    """Chatterbox emits 24 kHz mono PCM16; the watch plays one fixed 16 kHz stream."""
    import numpy as np

    with wave.open(io.BytesIO(wav_bytes), "rb") as w:
        rate = w.getframerate()
        channels = w.getnchannels()
        frames = w.readframes(w.getnframes())
    pcm = np.frombuffer(frames, dtype=np.int16)
    if channels > 1:
        pcm = pcm.reshape(-1, channels).mean(axis=1).astype(np.int16)
    if rate != target_rate:
        n = int(len(pcm) * target_rate / rate)
        pcm = np.interp(
            np.linspace(0, len(pcm), n, endpoint=False), np.arange(len(pcm)), pcm
        ).astype(np.int16)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(target_rate)
        out.writeframes(pcm.tobytes())
    return buf.getvalue()
