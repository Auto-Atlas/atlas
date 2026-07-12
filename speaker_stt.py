# speaker_stt.py
#
# The pipecat seam. Mixed into a SegmentedSTTService subclass (WhisperSTTService),
# it identifies the speaker from the full-utterance WAV that run_stt receives,
# BEFORE the TranscriptionFrame flows downstream — so the tier is set before any
# tool can fire. Keep run_stt an async generator; _identify is a separate coroutine.
#
from __future__ import annotations

import asyncio
import json
import os
import time
from pathlib import Path

from loguru import logger

import speaker_id
import speaker_state


def _profiles_path() -> Path:
    return Path(os.getenv("EVE_VOICEPRINTS", str(Path.home() / "eve-voiceprints" / "profiles.json")))


def _unknown_log_path() -> Path:
    env = os.getenv("EVE_UNKNOWN_LOG")
    return Path(env) if env else _profiles_path().parent / "unknown-utterances.log"


def _normalize_phrase(text: str) -> str:
    """Lowercase, drop punctuation, collapse whitespace — so 'Whiskey, Tango Foxtrot!'
    matches 'whiskey tango foxtrot' regardless of STT punctuation/casing."""
    cleaned = "".join(c if c.isalnum() else " " for c in text.lower())
    return " ".join(cleaned.split())


class SpeakerIDMixin:
    # subclasses may set these; defaults loaded lazily
    _profiles = None
    _embed_fn = None
    _reprompt_owner = False
    _capture_n = 0

    def _maybe_capture(self, audio: bytes):
        """Enrollment capture: when EVE_ENROLL_CAPTURE_DIR is set, save each live
        utterance WAV there so a profile can be built from EVE's own audio domain
        (enroll_speaker --wav-dir). Off by default — never dumps audio otherwise."""
        d = os.getenv("EVE_ENROLL_CAPTURE_DIR")
        if not d:
            return
        try:
            out = Path(d)
            out.mkdir(parents=True, exist_ok=True)
            SpeakerIDMixin._capture_n += 1
            stamp = time.strftime("%Y%m%d-%H%M%S")
            (out / f"utt-{stamp}-{SpeakerIDMixin._capture_n:04d}.wav").write_bytes(audio)
        except Exception as e:
            logger.warning(f"speaker_stt: enrollment capture failed ({e})")

    def _load_profiles_once(self):
        if self._profiles is None:
            self._profiles = speaker_id.load_profiles(_profiles_path())
        return self._profiles

    async def _identify(self, audio: bytes):
        profiles = self._load_profiles_once()
        threshold = float(os.getenv("EVE_SPEAKER_THRESHOLD", "0.75"))
        margin = float(os.getenv("EVE_SPEAKER_MARGIN", "0.07"))
        embed = self._embed_fn or speaker_id.embed
        try:
            emb = await asyncio.to_thread(embed, audio)
        except Exception as e:
            logger.warning(f"speaker_stt: embed failed ({e}); failing closed to unknown")
            speaker_state.set_current(None, "unknown", 0.0)
            return
        m = speaker_id.identify(emb, profiles, threshold)
        # Owner LATCH: voice-ID is a SOFT gate, not per-utterance hard auth. Once the
        # owner is confidently recognized, hold owner for a sticky window so a single
        # borderline utterance can't flip mid-conversation to the guest persona
        # ("you don't recognize this voice" — observed 2026-06-25 mid-brief). Each
        # confident owner match refreshes the latch. EVE_OWNER_STICKY_S=0 disables it.
        sticky_s = float(os.getenv("EVE_OWNER_STICKY_S", "1800"))
        if m.tier == "owner":
            speaker_state.set_current(m.name, m.tier, m.score)
            if sticky_s > 0:
                speaker_state.grant_owner_override(sticky_s)
            self._reprompt_owner = False
            return
        if sticky_s > 0 and speaker_state.reauth_active():
            # owner latched from a recent confident match — ignore this borderline read
            self._reprompt_owner = False
            return
        speaker_state.set_current(m.name, m.tier, m.score)
        # near-threshold owner near-miss -> arm a single re-prompt instead of a flat refuse
        self._reprompt_owner = (
            m.tier == "unknown"
            and threshold - margin <= m.score < threshold
        )
        if m.tier == "unknown":
            self._log_unknown(m.score)

    def _log_unknown(self, score: float):
        try:
            path = _unknown_log_path()
            path.parent.mkdir(parents=True, exist_ok=True)
            with open(path, "a", encoding="utf-8") as f:
                f.write(json.dumps({"at": time.strftime("%Y-%m-%d %H:%M:%S"),
                                    "score": round(score, 3)}) + "\n")
        except Exception as e:
            logger.warning(f"speaker_stt: could not write unknown log ({e})")

    async def run_stt(self, audio: bytes):
        self._maybe_capture(audio)
        await self._identify(audio)
        phrase = _normalize_phrase(os.getenv("EVE_OWNER_PHRASE", ""))
        ttl = float(os.getenv("EVE_OWNER_PHRASE_TTL_S", "120"))
        async for frame in super().run_stt(audio):
            # Owner-phrase recovery: if the transcript contains the spoken code word,
            # grant a short, time-boxed owner override (covers the invoice + confirm
            # flow) even if the voice match was low. Overhearable by design.
            text = getattr(frame, "text", None)
            if phrase and text and phrase in _normalize_phrase(text):
                speaker_state.grant_owner_override(ttl)
                logger.info("owner phrase recognized — owner override granted")
            yield frame
