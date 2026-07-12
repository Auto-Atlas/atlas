#
# Speech factory — side-effect-free home for EVE's STT/TTS builders and the
# shared speech processors, so every body (bot.py desktop, phone_bot.py phone,
# jetson_bot.py) imports them WITHOUT importing phone_bot (which binds a
# single-instance lock socket and sys.exit(111)s at module load).
#
# Extracted verbatim from phone_bot.py (no behaviour change). The JARVIS_STT /
# JARVIS_TTS switch lives here; Jetson-only backends (Riva) are imported lazily
# inside the builders so this module stays import-clean on Windows/Linux/x86.
#
import os
import re
import time
from collections import deque
from difflib import SequenceMatcher

from loguru import logger

import mic_control

from pipecat.frames.frames import (
    BotStartedSpeakingFrame,
    BotStoppedSpeakingFrame,
    InputAudioRawFrame,
    TTSTextFrame,
    TranscriptionFrame,
)
from pipecat.processors.frame_processor import FrameProcessor
from pipecat.processors.aggregators.llm_response_universal import LLMAssistantAggregator
from pipecat.services.kokoro.tts import KokoroTTSService
from pipecat.services.whisper.stt import WhisperSTTService
from pipecat.utils.text.markdown_text_filter import MarkdownTextFilter

from jarvis_core import trim_messages


# ---- Model preload ----------------------------------------------------------
# Both services load their models at CONSTRUCTION time, which used to happen
# per connection: ~15s of dead air on first connect, and a session takeover
# briefly held two Whisper models in VRAM. Share one loaded model per process.

class SharedWhisperSTT(WhisperSTTService):
    """WhisperSTTService whose underlying faster-whisper model is loaded once
    per process and shared by every session (one session at a time)."""

    _shared_model = None

    def _load(self):
        if SharedWhisperSTT._shared_model is None:
            super()._load()
            SharedWhisperSTT._shared_model = self._model
        else:
            self._model = SharedWhisperSTT._shared_model


# Speaker-ID variant: the mixin only enters the MRO when gating is enabled (see
# _build_stt), so EVE_SPEAKER_ID=disabled runs the plain shared model with no
# embedding code in the hot path. Base keeps its shared-model memoization.
from speaker_stt import SpeakerIDMixin


class SpeakerAwareSharedWhisperSTT(SpeakerIDMixin, SharedWhisperSTT):
    pass


# KokoroTTSService builds its engine inline in __init__ with no load hook, so
# memoize the engine constructor instead — same outcome, one ONNX session per
# process reused across sessions.
import pipecat.services.kokoro.tts as _kokoro_mod

_kokoro_engines: dict = {}
_kokoro_engine_cls = _kokoro_mod.Kokoro


def _cached_kokoro(model_path, voices_path, *args, **kwargs):
    key = (str(model_path), str(voices_path))
    if key not in _kokoro_engines:
        _kokoro_engines[key] = _kokoro_engine_cls(model_path, voices_path, *args, **kwargs)
    return _kokoro_engines[key]


_kokoro_mod.Kokoro = _cached_kokoro


# --- Half-duplex mic gate (ported from bot.py) ------------------------------
# The phone is ALWAYS on speakerphone with no acoustic echo cancellation, so the
# mic hears EVE's own TTS and she answers herself in a loop (observed 2026-06-22:
# "I keep real tasks to the family" x forever). This drops mic audio while the bot
# is speaking, plus a short tail for room reverb. Trade-off: no barge-in (you can't
# talk over her) until real AEC is in place. Phone default is ON; override with
# JARVIS_PHONE_HALF_DUPLEX=0.
class MicGate(FrameProcessor):
    def __init__(self, tail_s: float, max_gate_s: float = 30.0):
        super().__init__()
        self._tail_s = tail_s
        self._max_gate_s = max_gate_s
        self._bot_speaking = False
        self._open_after = 0.0
        self._gate_deadline = 0.0  # watchdog: hard ceiling on a single closed-gate stretch

    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        if isinstance(frame, BotStartedSpeakingFrame):
            self._bot_speaking = True
            # Arm the watchdog: if BotStoppedSpeaking is ever dropped, the gate would
            # otherwise stay closed forever and deafen EVE. Force it open after the ceiling.
            self._gate_deadline = time.monotonic() + self._max_gate_s
        elif isinstance(frame, BotStoppedSpeakingFrame):
            self._bot_speaking = False
            self._open_after = time.monotonic() + self._tail_s
        if isinstance(frame, InputAudioRawFrame):
            # Hard mute (e.g. during the morning-brief monologue): drop ALL mic audio
            # so EVE's own speakerphone echo can't be transcribed into a self-reply loop.
            if mic_control.muted():
                return
            now = time.monotonic()
            # Watchdog tripped: a speaking stretch ran past the ceiling -> assume the stop
            # frame was lost, reset state, and let the mic through so EVE isn't stuck deaf.
            if self._bot_speaking and now >= self._gate_deadline:
                logger.warning(
                    f"MicGate watchdog: bot 'speaking' > {self._max_gate_s:.0f}s with no "
                    "stop frame — force-opening mic (dropped BotStoppedSpeakingFrame?)"
                )
                self._bot_speaking = False
                self._open_after = 0.0
            elif self._bot_speaking or now < self._open_after:
                return  # mic is gated while EVE talks (and briefly after)
        await self.push_frame(frame, direction)


# --- Transcript echo guard ---------------------------------------------------
# Even with the MicGate, speakerphone PLAYBACK lags the server's
# BotStoppedSpeaking (network + client jitter buffer), so the tail can miss the
# end of a long sentence and Whisper transcribes EVE's own voice as the user
# (observed 2026-07-07: "While Hermes gathers the details..." answered in a
# loop). This is the TEXT-level backstop: drop a user transcription that is a
# (fuzzy or partial) echo of something the bot spoke in the recent window,
# regardless of audio timing. Short utterances ("yes") are never guarded — a
# real confirmation must not be eaten just because EVE said the word recently.
class EchoGuard(FrameProcessor):
    """Sits right after STT; [BotEchoRecorder] feeds it what the bot said."""

    MIN_WORDS = 3

    def __init__(self, window_s: float = 20.0, ratio: float = 0.8):
        super().__init__()
        self._window_s = window_s
        self._ratio = ratio
        self._recent: deque = deque()  # (normalized bot text, monotonic ts)

    @staticmethod
    def _norm(text: str) -> str:
        return " ".join(re.sub(r"[^a-z0-9 ]+", " ", text.lower()).split())

    def record_bot_text(self, text: str) -> None:
        now = time.monotonic()
        norm = self._norm(text)
        if norm:
            self._recent.append((norm, now))
        while self._recent and now - self._recent[0][1] >= self._window_s:
            self._recent.popleft()

    def is_echo(self, text: str) -> bool:
        norm = self._norm(text)
        if len(norm.split()) < self.MIN_WORDS:
            return False
        now = time.monotonic()
        for bot, ts in self._recent:
            if now - ts >= self._window_s:
                continue
            if norm in bot or SequenceMatcher(None, norm, bot).ratio() >= self._ratio:
                return True
        return False

    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        if isinstance(frame, TranscriptionFrame) and self.is_echo(frame.text or ""):
            logger.info(f"EchoGuard: dropped self-echo transcription {frame.text!r}")
            return
        await self.push_frame(frame, direction)


class BotEchoRecorder(FrameProcessor):
    """Sits after TTS; passes every frame through and tells the [EchoGuard]
    what the bot actually said (TTSTextFrame is the spoken text, post-filter)."""

    def __init__(self, guard: EchoGuard):
        super().__init__()
        self._guard = guard

    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        if isinstance(frame, TTSTextFrame):
            self._guard.record_bot_text(frame.text or "")
        await self.push_frame(frame, direction)


# --- Context-trimming assistant aggregator (shared by phone + Jetson bodies) ---
# Moved here so jetson_bot can reuse it WITHOUT importing phone_bot. Pure-Python
# (trim_messages + LLMAssistantAggregator), no audio deps.
class TrimmingAssistantAggregator(LLMAssistantAggregator):
    """Assistant aggregator that trims the shared context after every committed
    turn. The desktop loop (bot.py) trims in its announce() path; the phone loop
    had NO trim at all — a long call grew the history unbounded until the SYSTEM
    PROMPT (persona + tool rules + honesty/safety contract) fell out of the top
    of the model's window and EVE lost her contract mid-conversation (Ollama
    truncates from the top silently). Trimming here, at the natural per-turn
    boundary, always preserves the protected head."""

    def __init__(self, *args, protected_head: int, **kwargs):
        super().__init__(*args, **kwargs)
        self._protected_head = protected_head

    async def _handle_push_aggregation(self):
        await super()._handle_push_aggregation()
        msgs = self.context.get_messages()
        trimmed = trim_messages(msgs, self._protected_head)
        if len(trimmed) != len(msgs):
            self.context.set_messages(trimmed)
            logger.info(f"Phone context trimmed: {len(msgs)} -> {len(trimmed)} messages")


def _build_stt() -> WhisperSTTService:
    # Jetson body selects Riva (NVIDIA-optimized streaming ASR). Additive — the
    # default stays Whisper. riva_stt imports riva.client lazily, so this branch
    # is import-clean off-Jetson (only constructed when JARVIS_STT=riva).
    if os.getenv("JARVIS_STT", "whisper").lower() == "riva":
        from riva_stt import RivaSTTService
        return RivaSTTService(sample_rate=int(os.getenv("RIVA_ASR_RATE", "16000")))
    # int8 by default: the desktop loop already holds a float16 Whisper on the
    # 12 GB card; the phone session rides alongside in a smaller footprint.
    common = dict(
        device=os.getenv("WHISPER_DEVICE", "cuda"),
        compute_type=os.getenv("PHONE_WHISPER_COMPUTE", "int8"),
    )
    if os.getenv("EVE_SPEAKER_ID", "enabled") == "disabled":
        return SharedWhisperSTT(**common)                 # no mixin in the MRO
    try:
        import speaker_id
        speaker_id.preload()
        return SpeakerAwareSharedWhisperSTT(**common)
    except ImportError as e:
        # Don't brick the phone loop on a missing dep — fall back to plain shared
        # Whisper (fail-closed: gating inactive). Loud, actionable warning.
        logger.warning(f"speaker-ID deps missing ({e}); plain Whisper, gating "
                       "INACTIVE. Run: pip install resemblyzer")
        return SharedWhisperSTT(**common)


def _build_tts():
    # Same markdown stripping as the desktop loop — qwen3 sneaks **bold**
    # past the prompt rule, and "asterisk asterisk" in your ear ruins it.
    # Same JARVIS_TTS switch as the desktop loop too, so both bodies speak
    # with the same voice (kokoro in-process, or the Chatterbox-Turbo server).
    tts = os.getenv("JARVIS_TTS", "kokoro").lower()
    if tts == "riva":
        # Jetson self-contained voice (no extra server). riva_tts imports
        # riva.client lazily — import-clean off-Jetson.
        from riva_tts import RivaTTSService
        return RivaTTSService(sample_rate=int(os.getenv("RIVA_TTS_RATE", "22050")),
                              text_filters=[MarkdownTextFilter()])
    if tts == "chatterbox":
        from chatterbox_tts import ChatterboxTTSService

        return ChatterboxTTSService(
            api_key=os.getenv("JARVIS_TTS_API_KEY", "not-needed"),
            base_url=os.getenv("JARVIS_TTS_BASE_URL", "http://127.0.0.1:8004/v1"),
            voice=os.getenv("JARVIS_TTS_VOICE", "Emily.wav"),
            model=os.getenv("JARVIS_TTS_MODEL", "tts-1"),
            text_filters=[MarkdownTextFilter()],
        )
    return KokoroTTSService(
        settings=KokoroTTSService.Settings(voice=os.getenv("KOKORO_VOICE", "af_heart")),
        text_filters=[MarkdownTextFilter()],
    )
