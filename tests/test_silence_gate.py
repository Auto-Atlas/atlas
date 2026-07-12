# Tests for the SilenceController + SilenceGate — the transcription-level gate that keeps
# EVE quiet in silence mode until the OWNER says a wake phrase, then engages for a window.
import asyncio
import importlib
import os
import tempfile

import pytest


@pytest.fixture
def sm(monkeypatch):
    d = tempfile.mkdtemp()
    db = os.path.join(d, "approvals.db")
    import approval_store
    monkeypatch.setenv("EVE_APPROVAL_DB", db)
    approval_store.set_db_path(db)
    import silence_mode
    importlib.reload(silence_mode)
    # Pin the assistant's name — the default wake phrase derives from it (never a literal).
    import persona
    monkeypatch.setattr(persona, "ASSISTANT_NAME", "Eve")
    return silence_mode


@pytest.fixture
def ctrl(sm):
    c = sm.SilenceController()
    # Deterministic clock for window math.
    c._clock = _FakeClock()
    return c


class _FakeClock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t

    def advance(self, dt):
        self.t += dt


# ---- gate CLOSED drops non-wake utterances ----------------------------------

def test_silence_off_passes_everything(ctrl, sm):
    assert sm.enabled() is False
    passed, woke = ctrl.decide("anything at all", "owner")
    assert passed is True and woke is False


def test_closed_gate_drops_non_wake(ctrl, sm):
    sm.set_enabled(True)
    passed, woke = ctrl.decide("what's the weather", "owner")
    assert passed is False and woke is False


# ---- OWNER wake opens the gate AND passes that same utterance ----------------

def test_owner_wake_opens_and_passes_that_utterance(ctrl, sm):
    sm.set_enabled(True)
    passed, woke = ctrl.decide("eve what's the weather", "owner")
    assert passed is True and woke is True
    assert ctrl.is_open() is True


def test_followups_flow_without_repeating_phrase(ctrl, sm):
    sm.set_enabled(True)
    ctrl.decide("eve what's the weather", "owner")          # wakes
    passed, woke = ctrl.decide("and what about tomorrow", "owner")  # no phrase
    assert passed is True and woke is False                 # window is open


# ---- window expiry re-closes ------------------------------------------------

def test_window_expiry_recloses(ctrl, sm):
    sm.set_enabled(True)
    ctrl.decide("eve hello", "owner")                       # window = 15s
    ctrl._clock.advance(16.0)
    assert ctrl.is_open() is False
    passed, woke = ctrl.decide("still there", "owner")
    assert passed is False and woke is False                # closed again


def test_activity_extends_window(ctrl, sm):
    sm.set_enabled(True)
    ctrl.decide("eve hello", "owner")
    ctrl._clock.advance(10.0)
    ctrl.decide("keep going", "owner")                      # extends by another 15
    ctrl._clock.advance(10.0)
    assert ctrl.is_open() is True                           # 20s elapsed but extended


# ---- NON-owner phrase does NOT wake -----------------------------------------

def test_non_owner_phrase_does_not_wake(ctrl, sm):
    sm.set_enabled(True)
    passed, woke = ctrl.decide("eve are you there", "kid")
    assert passed is False and woke is False
    assert ctrl.is_open() is False


def test_open_gate_passes_non_owner_normally(ctrl, sm):
    # When the gate is already OPEN, normal speaker rules apply unchanged — a non-owner
    # follow-up passes through (tool_policy still gates any tool the LLM tries).
    sm.set_enabled(True)
    ctrl.decide("eve hello", "owner")                       # owner wakes
    passed, woke = ctrl.decide("can I ask something", "known")
    assert passed is True and woke is False


# ---- live toggle mid-session ------------------------------------------------

def test_live_toggle_mid_session(ctrl, sm):
    # Silence OFF -> everything passes.
    passed, _ = ctrl.decide("hello", "owner")
    assert passed is True
    # Flip ON live -> non-wake now dropped, no restart.
    sm.set_enabled(True)
    passed, _ = ctrl.decide("hello again", "owner")
    assert passed is False
    # Flip OFF live -> passes again.
    sm.set_enabled(False)
    passed, _ = ctrl.decide("hello once more", "owner")
    assert passed is True


# ---- proactive hold: wait_until_open blocks while closed, releases on wake ---

def test_wait_until_open_passes_immediately_when_silence_off(ctrl, sm):
    async def go():
        await asyncio.wait_for(ctrl.wait_until_open(), timeout=1.0)
    asyncio.run(go())   # must not hang


def test_wait_until_open_blocks_until_wake(ctrl, sm, monkeypatch):
    monkeypatch.setenv("EVE_SILENCE_HOLD_POLL_S", "0.01")
    importlib.reload(sm)
    c = sm.SilenceController()
    clk = _FakeClock()
    c._clock = clk
    sm.set_enabled(True)

    async def go():
        released = asyncio.Event()

        async def waiter():
            await c.wait_until_open()
            released.set()

        t = asyncio.create_task(waiter())
        await asyncio.sleep(0.05)
        assert not released.is_set()          # still held (gate closed)
        # Owner wake opens the gate:
        c.decide("eve you there", "owner")
        await asyncio.wait_for(released.wait(), timeout=1.0)
        t.cancel()

    asyncio.run(go())


def test_wait_until_open_releases_when_silence_disabled(ctrl, sm, monkeypatch):
    monkeypatch.setenv("EVE_SILENCE_HOLD_POLL_S", "0.01")
    importlib.reload(sm)
    c = sm.SilenceController()
    c._clock = _FakeClock()
    sm.set_enabled(True)

    async def go():
        released = asyncio.Event()

        async def waiter():
            await c.wait_until_open()
            released.set()

        t = asyncio.create_task(waiter())
        await asyncio.sleep(0.05)
        assert not released.is_set()
        sm.set_enabled(False)                 # owner said "you can talk again"
        await asyncio.wait_for(released.wait(), timeout=1.0)
        t.cancel()

    asyncio.run(go())


# ---- the real FrameProcessor over pipecat's harness -------------------------

def test_gate_processor_drops_closed_and_passes_wake(sm):
    from pipecat.frames.frames import TranscriptionFrame
    from pipecat.tests.utils import run_test

    import speaker_state

    async def go():
        sm.set_enabled(True)
        c = sm.SilenceController()
        gate = sm.SilenceGate(c)
        speaker_state.set_current("Owner", "owner", 1.0)

        # A non-wake utterance while closed is DROPPED (never reaches downstream).
        received, _ = await run_test(
            gate,
            frames_to_send=[TranscriptionFrame("just the weather please", "u", "t")],
            expected_down_frames=[],
        )

        # A wake utterance passes through unchanged.
        gate2 = sm.SilenceGate(c)  # fresh (harness owns lifecycle); reuse controller closed
        c.close()
        received2, _ = await run_test(
            gate2,
            frames_to_send=[TranscriptionFrame("eve what's the weather", "u", "t")],
            expected_down_frames=[TranscriptionFrame],
        )
        texts = [f.text for f in received2 if isinstance(f, TranscriptionFrame)]
        assert "eve what's the weather" in texts

    asyncio.run(go())
