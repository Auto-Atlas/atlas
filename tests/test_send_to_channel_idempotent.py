# tests/test_send_to_channel_idempotent.py
#
# Last of the double-fire class (after the SMS + invoice double-send fixes,
# 2026-06-22). send_to_channel is a needs_confirmation tool: tool_policy stages
# the call and a confirmed=true release sends it, single-fire PER STAGE — but the
# model can RE-STAGE the identical (channel, message) (a post-denial / threshold
# retry, or a "do it again" turn) and confirm a second time, which re-POSTed to
# the channel: a duplicate message that can't be unsent. The confirmation GATE was
# single-fire; the EXECUTE step was not.
#
# These tests pin a sent-once guard keyed to (channel, message), set ONLY after the
# channel send succeeds:
#   - a re-stage + re-confirm of an ALREADY-SENT message must NOT POST again;
#   - a genuinely different message still sends normally;
#   - a FAILED send is NOT guarded, so a real retry can still send.
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import channel_tool  # noqa: E402


class FakeParams:
    """Minimal stand-in for FunctionCallParams: arguments + a capturing
    async result_callback. After the handler runs, `delivered` holds the dict
    the tool passed back to the model."""

    def __init__(self, arguments):
        self.arguments = arguments
        self.delivered = None

    async def result_callback(self, result):
        self.delivered = result


def _fake_client(**methods):
    """Build a fake OpenJarvisClient class whose async methods are taken from
    `methods` (each a coroutine-returning callable)."""

    class FakeClient:
        def __init__(self, *a, **kw):
            pass

    for name, fn in methods.items():
        setattr(FakeClient, name, fn)
    return FakeClient


@pytest.fixture(autouse=True)
def _reset_channel_state():
    # Clean process-scoped state between tests so the guard doesn't leak across cases.
    if hasattr(channel_tool, "_sent_once"):
        channel_tool._sent_once.clear()
    yield


@pytest.mark.asyncio
async def test_repeated_send_of_same_message_posts_only_once(monkeypatch):
    """The double-send repro: the SAME (channel, message) is released twice
    (re-stage + re-confirm). The channel must be POSTed EXACTLY ONCE; the second
    release reports it was already sent rather than sending a duplicate."""
    sends: list[tuple[str, str]] = []

    async def channel_send(self, channel, content, conversation_id=None):
        sends.append((channel, content))
        return {"status": "sent", "channel": channel}

    monkeypatch.setattr(channel_tool, "OpenJarvisClient", _fake_client(channel_send=channel_send))

    p1 = FakeParams({"channel": "telegram", "message": "on my way"})
    await channel_tool.handle_send_to_channel(p1)
    assert p1.delivered["ok"] is True and p1.delivered.get("sent") is True

    # Post-denial retry: the model re-stages the IDENTICAL message and confirms again.
    p2 = FakeParams({"channel": "telegram", "message": "on my way"})
    await channel_tool.handle_send_to_channel(p2)

    assert len(sends) == 1, f"channel double-sent: {sends!r}"
    assert p2.delivered.get("already_sent") is True
    assert p2.delivered.get("sent") is not True


@pytest.mark.asyncio
async def test_different_message_still_sends(monkeypatch):
    """A genuinely different message (or different channel) still sends normally."""
    sends: list[tuple[str, str]] = []

    async def channel_send(self, channel, content, conversation_id=None):
        sends.append((channel, content))
        return {"status": "sent", "channel": channel}

    monkeypatch.setattr(channel_tool, "OpenJarvisClient", _fake_client(channel_send=channel_send))

    p1 = FakeParams({"channel": "telegram", "message": "first message"})
    await channel_tool.handle_send_to_channel(p1)
    assert p1.delivered["ok"] is True

    p2 = FakeParams({"channel": "telegram", "message": "a totally different message"})
    await channel_tool.handle_send_to_channel(p2)

    assert len(sends) == 2
    assert p2.delivered["ok"] is True and p2.delivered.get("sent") is True


@pytest.mark.asyncio
async def test_failed_send_is_not_guarded(monkeypatch):
    """If the channel send raised (not delivered), the draft is NOT marked sent —
    a genuine retry must be able to send. (Guard set only after real success.)"""
    sends: list[tuple[str, str]] = []
    state = {"n": 0}

    async def channel_send(self, channel, content, conversation_id=None):
        state["n"] += 1
        if state["n"] == 1:
            raise RuntimeError("daemon unreachable")
        sends.append((channel, content))
        return {"status": "sent", "channel": channel}

    monkeypatch.setattr(channel_tool, "OpenJarvisClient", _fake_client(channel_send=channel_send))

    p1 = FakeParams({"channel": "slack", "message": "hi"})
    await channel_tool.handle_send_to_channel(p1)
    assert p1.delivered["ok"] is False                      # failed, not guarded

    p2 = FakeParams({"channel": "slack", "message": "hi"})
    await channel_tool.handle_send_to_channel(p2)

    assert len(sends) == 1                                  # retry actually sent
    assert p2.delivered["ok"] is True and p2.delivered.get("sent") is True
