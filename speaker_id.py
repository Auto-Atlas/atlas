# speaker_id.py
#
# Speaker embedding + closed-set matching. PURE (no pipecat). Resemblyzer is
# imported lazily inside embed()/preload(), so this module — and the identify()
# logic the security gate leans on — imports and tests without the audio stack.
#
from __future__ import annotations

import io
import json
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from loguru import logger

# Bump either when the encoder or the embed() preprocessing changes; stored
# embeddings then mismatch and load_profiles() drops them (fail-closed) instead
# of silently scoring garbage.
ENCODER_VERSION = "resemblyzer-1"
PREPROCESSING_VERSION = "wav-int16-mono-16k-1"

_encoder = None  # module-global VoiceEncoder singleton (resemblyzer targets 16 kHz)


@dataclass(frozen=True)
class Profile:
    name: str
    tier: str
    embedding: np.ndarray


@dataclass(frozen=True)
class Match:
    name: str | None
    tier: str
    score: float


def identify(emb: np.ndarray, profiles: list[Profile], threshold: float) -> Match:
    """Cosine-similarity best match. Below threshold OR no profiles -> unknown."""
    best = Match(None, "unknown", 0.0)
    for p in profiles:
        score = float(np.dot(emb, p.embedding))  # both L2-normalized -> cosine
        if score > best.score:
            best = Match(p.name, p.tier, score)
    if best.name is None or best.score < threshold:
        return Match(None, "unknown", best.score)
    return best


def load_profiles(path: Path) -> list[Profile]:
    path = Path(path)
    if not path.is_file():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if (data.get("encoder_version") != ENCODER_VERSION
                or data.get("preprocessing_version") != PREPROCESSING_VERSION):
            logger.warning(
                f"speaker_id: {path.name} version mismatch — ignoring all profiles "
                "(re-enroll). Everyone is 'unknown' until then."
            )
            return []
        out = []
        for rec in data.get("profiles", []):
            emb = np.asarray(rec["embedding"], dtype=np.float32)
            out.append(Profile(str(rec["name"]), str(rec["tier"]), emb))
        return out
    except Exception as e:  # malformed -> fail closed, never raise into the loop
        logger.warning(f"speaker_id: could not read {path} ({e}); no profiles loaded")
        return []


def preload() -> None:
    """Construct + GPU-resident the single VoiceEncoder once, at process boot."""
    global _encoder
    if _encoder is None:
        from resemblyzer import VoiceEncoder
        _encoder = VoiceEncoder()
        logger.info("speaker_id: VoiceEncoder preloaded")


def _decode_wav(wav_bytes: bytes):
    """WAV bytes -> (interleaved int16 pcm, sample_rate, channels)."""
    with wave.open(io.BytesIO(wav_bytes), "rb") as w:
        return (np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16),
                w.getframerate(), w.getnchannels())


def _rewrap(pcm: np.ndarray, sr: int, channels: int) -> bytes:
    """Wrap an interleaved int16 pcm slice back into WAV bytes (for windowing)."""
    b = io.BytesIO()
    with wave.open(b, "wb") as o:
        o.setnchannels(channels)
        o.setsampwidth(2)
        o.setframerate(sr)
        o.writeframes(pcm.tobytes())
    return b.getvalue()


def embed(wav_bytes: bytes) -> np.ndarray:
    """WAV bytes (what run_stt receives) -> L2-normalized 256-d embedding.
    Preprocessing is load-bearing: read the real sample rate, int16->float32,
    resample to 16k. This is the LIVE per-utterance path (utterances are short)."""
    from resemblyzer import preprocess_wav
    pcm, sr, channels = _decode_wav(wav_bytes)
    if channels > 1:
        pcm = pcm.reshape(-1, channels).mean(axis=1).astype(np.int16)
    wav = pcm.astype(np.float32) / 32768.0
    # preprocess_wav handles resampling to 16k + normalization given source_sr.
    wav = preprocess_wav(wav, source_sr=sr)
    preload()
    emb = _encoder.embed_utterance(wav)
    emb = np.asarray(emb, dtype=np.float32)
    return emb / np.linalg.norm(emb)


def _normalize_mean(embs: list[np.ndarray]) -> np.ndarray:
    """Mean of unit embeddings, renormalized. Pure — testable without resemblyzer."""
    avg = np.mean(np.stack(embs), axis=0)
    return (avg / np.linalg.norm(avg)).astype(np.float32)


def embed_profile_files(wav_bytes_list, *, _embed=None) -> np.ndarray:
    """Build a profile by averaging embeddings of MANY short clips — used to enroll
    from EVE's own live-captured utterances, so enrollment and live matching share
    the same audio domain (an offline-recorded file lands the voice elsewhere)."""
    _embed = _embed or embed
    embs = [_embed(b) for b in wav_bytes_list if b]
    if not embs:
        raise ValueError("no audio to enroll from")
    return _normalize_mean(embs)


def embed_profile(wav_bytes: bytes, window_s: float = 4.0, *, _embed=None) -> np.ndarray:
    """ENROLLMENT path. A single long-clip embedding sits far from the SHORT
    per-utterance embeddings EVE sees live (same voice scored 0.39 in the field).
    So build the profile from short ~window_s windows and average them — that
    matches the live representation (same voice then scores ~0.96) while keeping
    strangers far. Clips too short to window fall back to one embed."""
    _embed = _embed or embed
    pcm, sr, ch = _decode_wav(wav_bytes)
    win = int(sr * ch * window_s)
    if win <= 0 or len(pcm) < int(win * 1.5):
        return _embed(wav_bytes)
    embs = [_embed(_rewrap(pcm[i:i + win], sr, ch))
            for i in range(0, len(pcm) - win + 1, win)]
    return _normalize_mean(embs)
