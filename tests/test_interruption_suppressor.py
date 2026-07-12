# Unit test for phone_bot.InterruptionSuppressor — the half-duplex barge-in guard.
# Contract: process_frame swallows a frame iff (_active and it's an InterruptionFrame);
# everything else is pushed through. _active is decided ONCE at construction from the
# session's barge_in setting and the JARVIS_SUPPRESS_INTERRUPTIONS env override.
import asyncio

import pytest


@pytest.fixture
def phone_bot_mod():
    import phone_bot
    return phone_bot


def test_active_flag_decision(phone_bot_mod, monkeypatch):
    # Default (env unset -> "1"): suppress when barge-in is OFF, stand down when ON.
    monkeypatch.delenv("JARVIS_SUPPRESS_INTERRUPTIONS", raising=False)
    assert phone_bot_mod.InterruptionSuppressor(barge_in=False)._active is True
    assert phone_bot_mod.InterruptionSuppressor(barge_in=True)._active is False
    # Explicit env kill-switch disables suppression regardless of barge-in.
    monkeypatch.setenv("JARVIS_SUPPRESS_INTERRUPTIONS", "0")
    assert phone_bot_mod.InterruptionSuppressor(barge_in=False)._active is False


def test_swallows_interruption_only_when_active(phone_bot_mod, monkeypatch):
    from pipecat.frames.frames import InterruptionFrame, TTSSpeakFrame
    from pipecat.processors.frame_processor import FrameProcessor, FrameDirection

    async def _run(barge_in):
        monkeypatch.setenv("JARVIS_SUPPRESS_INTERRUPTIONS", "1")
        s = phone_bot_mod.InterruptionSuppressor(barge_in=barge_in)
        pushed = []

        async def _fake_push(frame, direction):
            pushed.append(frame)

        async def _noop_parent(self, frame, direction):
            return None

        # Bypass FrameProcessor bookkeeping (needs a live pipeline) and capture pushes.
        monkeypatch.setattr(FrameProcessor, "process_frame", _noop_parent, raising=True)
        monkeypatch.setattr(s, "push_frame", _fake_push, raising=True)

        d = FrameDirection.DOWNSTREAM
        await s.process_frame(InterruptionFrame(), d)
        await s.process_frame(TTSSpeakFrame("hi"), d)
        return pushed

    # Suppressing (barge_in OFF): the InterruptionFrame is swallowed, speech passes.
    pushed_off = asyncio.run(_run(False))
    assert not any(isinstance(f, InterruptionFrame) for f in pushed_off)
    assert any(isinstance(f, TTSSpeakFrame) for f in pushed_off)

    # Barge-in ON: the InterruptionFrame passes through (real interruption allowed).
    pushed_on = asyncio.run(_run(True))
    assert any(isinstance(f, InterruptionFrame) for f in pushed_on)
    assert any(isinstance(f, TTSSpeakFrame) for f in pushed_on)
