# silence_mode.py
#
# EVE's "silence mode" — stay completely quiet unless the OWNER says a wake phrase, then
# engage normally for a grace window so follow-ups flow, and go silent again after inactivity.
# The owner's ask: "a silence mode like don't talk unless I specifically say the hey help me, or
# don't talk unless I say a certain word."
#
# Two pieces live here:
#   1. The SETTINGS module (mirrors thinking_state.py): three keys on the shared approval_store
#      settings table so the app (approval_api process) and the voice loop share ONE source of
#      truth across processes. Reads are CACHED with a short TTL (the gate checks per-utterance,
#      never a SQLite connection per frame) and NEVER create the DB just to read a setting that
#      can't exist yet (db_exists guard, like thinking_state / tool_policy.remote_approval).
#   2. The SilenceController + SilenceGate: the transcription-level gate (placed AFTER STT,
#      BEFORE the LLM aggregator in the DESKTOP loop — bot.py) that drops non-wake user
#      transcriptions while silence is ON and the wake window is closed.
#
# HONESTY SPINE:
#   - FAIL-OPEN: if the settings store is unreadable, enabled() returns False (she behaves
#     NORMALLY — silence mode never bricks her voice) with a LOUD warning, not a silent swallow.
#   - Nothing owner-specific baked in: the default wake phrase is the assistant's OWN name,
#     derived from JARVIS_ASSISTANT_NAME (never a literal — a "Jarvis" install must not wake
#     only to "eve"); every value (phrases, window, on/off) is a configurable setting.
#   - Archive is NOT blinded: the gate sits DOWNSTREAM of STT, and the transcript archive taps
#     at the observer level (bridge.MetricsWSObserver sees STT's push BEFORE this gate), so a
#     dropped utterance is still broadcast/archived; the gate only withholds it from the LLM.
#
# WAKE-PHRASE gate vs pipecat's WakeCheckFilter: WakeCheckFilter is DEPRECATED (v0.0.106), has
# no live on/off toggle, no owner/speaker gating, a fixed (constructor) keepalive, mutates the
# frame text, and no proactive-hold seam — none of which fit. So this is a small custom
# processor in the MicGate (speech_factory.py) house style instead.
#
import asyncio
import json
import os
import time

from loguru import logger

# The default wake phrase is whatever the assistant is NAMED (JARVIS_ASSISTANT_NAME) — a
# literal here would bake one deployment's branding into the product (a "Jarvis" install
# must not wake only to "eve"). persona is import-light (env + strings, no pipecat); the
# attribute is read at CALL time so tests can pin the name without reload gymnastics.
import persona

_KEY_ENABLED = "silence_mode_enabled"
_KEY_PHRASES = "wake_phrases"
_KEY_WINDOW = "silence_wake_window_s"


def _default_phrases() -> list[str]:
    return [persona.ASSISTANT_NAME.lower()]


_DEFAULT_WINDOW_S = 15.0

_CACHE_TTL_S = float(os.getenv("EVE_SILENCE_CACHE_TTL_S", "2.0"))
_HOLD_POLL_S = float(os.getenv("EVE_SILENCE_HOLD_POLL_S", "0.2"))

# One cached snapshot for the three settings; refreshed together on TTL expiry.
_cache: tuple[float, bool, list[str], float] | None = None


def _normalize(text: str) -> str:
    """Lowercase, drop punctuation, collapse whitespace — so 'EVE, help me!' matches 'eve'
    regardless of STT casing/punctuation. Mirrors speaker_stt._normalize_phrase (the house
    pattern for phrase matching); duplicated (2 lines) to avoid importing a private helper."""
    cleaned = "".join(c if c.isalnum() else " " for c in text.lower())
    return " ".join(cleaned.split())


def _read_store() -> tuple[bool, list[str], float]:
    """Read the three settings from the store. FAIL-OPEN + LOUD on any store error: silence
    treated OFF (she keeps talking) so a bad disk never deafens/mutes her permanently."""
    try:
        import approval_store
        if not approval_store.db_exists():
            return False, _default_phrases(), _DEFAULT_WINDOW_S   # never set -> defaults
        raw_enabled = approval_store.get_setting(_KEY_ENABLED)
        enabled = str(raw_enabled).strip().lower() == "true" if raw_enabled is not None else False

        raw_phrases = approval_store.get_setting(_KEY_PHRASES)
        phrases = _parse_phrases(raw_phrases)

        raw_window = approval_store.get_setting(_KEY_WINDOW)
        window = _parse_window(raw_window)
        return enabled, phrases, window
    except Exception as e:
        logger.warning(
            f"silence_mode: settings store unreadable ({e}); FAILING OPEN — silence mode OFF, "
            "EVE speaks normally. Fix the approvals.db to re-enable quiet mode."
        )
        return False, _default_phrases(), _DEFAULT_WINDOW_S


def _parse_phrases(raw) -> list[str]:
    if raw is None:
        return _default_phrases()
    try:
        val = json.loads(raw)
        phrases = [str(p) for p in val if str(p).strip()]
        return phrases or _default_phrases()
    except (ValueError, TypeError) as e:
        logger.warning(
            f"silence_mode: wake_phrases is not valid JSON ({e}); using default {_default_phrases()}"
        )
        return _default_phrases()


def _parse_window(raw) -> float:
    if raw is None:
        return _DEFAULT_WINDOW_S
    try:
        w = float(raw)
        return w if w > 0 else _DEFAULT_WINDOW_S
    except (ValueError, TypeError):
        logger.warning(
            f"silence_mode: silence_wake_window_s {raw!r} is not a number; "
            f"using default {_DEFAULT_WINDOW_S}"
        )
        return _DEFAULT_WINDOW_S


def _snapshot() -> tuple[bool, list[str], float]:
    """Cached read for the per-utterance hot path (TTL <= 2s => a mid-session flip lands within
    the TTL, per the live-toggle requirement — the gate is NOT bound at session start)."""
    global _cache
    now = time.monotonic()
    if _cache is not None and (now - _cache[0]) < _CACHE_TTL_S:
        return _cache[1], _cache[2], _cache[3]
    enabled, phrases, window = _read_store()
    _cache = (now, enabled, phrases, window)
    return enabled, phrases, window


def enabled() -> bool:
    return _snapshot()[0]


def wake_phrases() -> list[str]:
    return _snapshot()[1]


def window_s() -> float:
    return _snapshot()[2]


def set_enabled(value: bool) -> None:
    """Persist the toggle (from the app via approval_api, or the set_silence_mode voice tool)."""
    import approval_store
    approval_store.set_setting(_KEY_ENABLED, "true" if value else "false")
    _invalidate()


def _invalidate() -> None:
    global _cache
    _cache = None


def is_wake(text: str, tier: str) -> bool:
    """True iff silence is ON, the utterance contains a configured wake phrase, AND the current
    speaker tier is 'owner'. Non-owner speakers never wake her out of silence (a kid saying the
    phrase does nothing). Case-insensitive, punctuation-tolerant, phrase-contained-in-utterance."""
    if not enabled():
        return False
    if tier != "owner":
        return False
    norm = _normalize(text)
    if not norm:
        return False
    padded = f" {norm} "                      # word-boundary: 'eve' must not match 'evening'
    for phrase in wake_phrases():
        np = _normalize(phrase)
        if np and f" {np} " in padded:
            return True
    return False


class SilenceController:
    """Runtime state for the wake window + the proactive-hold gate. Shared by the SilenceGate
    (transcription path) and bot.py's announce() (proactive path). Pure-Python, testable."""

    def __init__(self):
        self._open_until = 0.0
        self._clock = time.monotonic         # patchable in tests
        self._poll_s = _HOLD_POLL_S

    def is_open(self) -> bool:
        """True while the wake window is live (owner engaged)."""
        return self._clock() < self._open_until

    def note_activity(self) -> None:
        """Open (or extend) the wake window: stays open for window_s after each exchange."""
        self._open_until = self._clock() + window_s()

    def close(self) -> None:
        self._open_until = 0.0

    def decide(self, text: str, tier: str) -> tuple[bool, bool]:
        """Decide whether a user transcription passes to the LLM. Returns (pass_frame, woke):
          - silence OFF                          -> (True,  False)  pass everything
          - gate OPEN                            -> (True,  False)  window live; extend it
          - closed + owner wake phrase           -> (True,  True)   open window, pass THIS one
          - closed + anything else               -> (False, False)  dropped (stays silent)
        When the gate is OPEN, normal speaker rules apply unchanged (a non-owner follow-up
        passes; tool_policy still gates any tool)."""
        if not enabled():
            return True, False
        if self.is_open():
            self.note_activity()               # follow-ups keep the window alive
            return True, False
        if is_wake(text, tier):
            self.note_activity()               # owner wake opens the window
            return True, True
        return False, False

    async def wait_until_open(self) -> None:
        """Proactive HOLD seam. Called by announce() while it holds announce_lock: block here
        (preserving arrival order via the lock's FIFO fairness) until the gate opens on the next
        owner wake OR silence mode is turned off. The caller has NOT marked anything delivered
        yet (it hasn't returned), so an item held here stays in its durable store and re-surfaces
        after a crash — QUEUED, not spoken, and NOT lost. Returns immediately when not holding."""
        while enabled() and not self.is_open():
            await asyncio.sleep(self._poll_s)


# The gate lives in the transcription seam of the DESKTOP loop (bot.py) ONLY. phone_bot.py and
# watch_bot.py are deliberate-OPEN surfaces (you pick up the phone / raise the watch to talk on
# purpose — there is no always-listening room to keep quiet), so they stay ungated this round.
def _transcription_frame_types():
    # Imported lazily so this module stays import-clean for the settings-only callers
    # (approval_api) that never touch pipecat.
    from pipecat.frames.frames import InterimTranscriptionFrame, TranscriptionFrame
    return TranscriptionFrame, InterimTranscriptionFrame


def _make_silence_gate_class():
    from pipecat.processors.frame_processor import FrameProcessor

    import speaker_state

    frame_types = _transcription_frame_types()

    class SilenceGate(FrameProcessor):
        """Transcription-level gate: drop user transcriptions from reaching the LLM while
        silence mode is ON and the wake window is closed. A no-op when silence is OFF (decide()
        passes everything), so it's always in the pipeline and the LIVE setting controls it."""

        def __init__(self, controller: "SilenceController"):
            super().__init__()
            self._c = controller

        async def process_frame(self, frame, direction):
            await super().process_frame(frame, direction)
            if isinstance(frame, frame_types):
                text = getattr(frame, "text", "") or ""
                tier = speaker_state.current_tier()
                passed, woke = self._c.decide(text, tier)
                if not passed:
                    # Honesty: the transcript was already archived/broadcast at the observer
                    # (STT's push, upstream of this gate) — we only withhold it from the LLM.
                    logger.debug(
                        f"silence gate: dropped user utterance (tier={tier!r}) — silence mode "
                        "ON, wake window closed"
                    )
                    return
                if woke:
                    logger.info(
                        "silence gate: owner wake phrase — engaging normally for the wake window"
                    )
            await self.push_frame(frame, direction)

    return SilenceGate


# Lazy singleton so `import silence_mode` never forces a pipecat import on the settings-only
# path (approval_api). SilenceGate(...) constructs the class on first use in bot.py.
def SilenceGate(controller: "SilenceController"):  # noqa: N802 (factory named like the class)
    cls = _make_silence_gate_class()
    return cls(controller)
