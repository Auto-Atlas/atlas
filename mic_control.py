"""Hard mic mute — a timed kill-switch the voice loop honors regardless of the
normal half-duplex gate.

On speakerphone with no acoustic echo cancellation, EVE's own TTS leaks into the
mic, gets transcribed as the user, and spawns a runaway self-reply loop. The
per-utterance half-duplex gate isn't enough during a long monologue (the brief):
between sentences the mic reopens and catches reverb. When EVE is about to deliver
a known-length monologue, she calls ``mute_for(seconds)`` and the MicGate drops
ALL input audio until then — so her voice cannot echo back no matter what.

Uses time.monotonic() to match MicGate. Process-local (one phone session at a time).
"""

from __future__ import annotations

import time

_mute_until: float = 0.0


def mute_for(seconds: float) -> None:
    """Hard-mute the mic for ``seconds`` from now (extends an existing mute, never
    shortens it)."""
    global _mute_until
    if seconds and seconds > 0:
        _mute_until = max(_mute_until, time.monotonic() + seconds)


def muted() -> bool:
    return time.monotonic() < _mute_until


def clear() -> None:
    global _mute_until
    _mute_until = 0.0
