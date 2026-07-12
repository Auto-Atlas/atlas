# tests/test_confirm_send_text_idempotent.py
#
# Live SAFETY bug (2026-06-22): after a speaker-ID denial + repeated "send it,
# send it" confirmations (and a threshold-lower retry), confirm_send_text fired
# TWICE and the same SMS went out 2x. The two-step prepare/confirm flow consumed
# its single stage before sending, but once the model re-staged the IDENTICAL
# message via prepare_text (the post-denial retry) a second confirm re-sent it.
#
# These tests pin the fix: a sent-once idempotency guard keyed to the staged
# payload — once a draft has been sent, a confirm for the SAME (phone, message)
# no-ops with an "already sent" result, while a genuinely NEW message still sends.
import asyncio
from dataclasses import dataclass
from typing import Callable, Optional

import pytest

import sms_tool


@dataclass
class FakeParams:
    arguments: dict
    delivered: object = None
    result_callback: Optional[Callable] = None

    def __post_init__(self):
        if self.result_callback is None:
            async def _capture(result, **kwargs):
                self.delivered = result
            self.result_callback = _capture


@pytest.fixture(autouse=True)
def _reset_sms_state(monkeypatch):
    # Clean process-scoped state between tests so guards don't leak across cases.
    sms_tool._pending = None
    sms_tool._pending_at = 0.0
    if hasattr(sms_tool, "_sent_once"):
        sms_tool._sent_once.clear()
    # Pretend a contact resolves cleanly to a fixed number.
    monkeypatch.setattr(
        sms_tool,
        "resolve",
        lambda name: {"status": "ok", "matches": [{"name": "Mike", "phone": "+15550001111"}]},
    )
    # MacroDroid is the default backend; satisfy its config check.
    monkeypatch.setattr(sms_tool, "MACRODROID_SEND_URL", "http://phone.local/webhook")
    yield


async def _stage(message="on my way"):
    p = FakeParams({"name": "Mike", "message": message})
    await sms_tool.handle_prepare_text(p)
    assert p.delivered.get("staged") is True
    return p


@pytest.mark.asyncio
async def test_repeated_confirm_after_restage_sends_only_once(monkeypatch):
    """The live double-send repro: stage -> confirm (sends), then the model
    re-stages the SAME message (post-denial / threshold retry) and confirms
    again. The gateway send must fire EXACTLY ONCE; the second confirm must
    report it was already sent rather than re-sending."""
    sends: list[tuple[str, str]] = []

    async def fake_send(phone, message):
        sends.append((phone, message))

    monkeypatch.setattr(sms_tool, "send_sms", fake_send)

    await _stage("on my way")
    c1 = FakeParams({})
    await sms_tool.handle_confirm_send_text(c1)
    assert c1.delivered["ok"] is True and c1.delivered.get("sent") is True

    # Post-denial retry: the model re-stages the IDENTICAL message, then a second
    # "send it" confirms again.
    await _stage("on my way")
    c2 = FakeParams({})
    await sms_tool.handle_confirm_send_text(c2)

    assert len(sends) == 1, f"SMS double-sent: {sends!r}"
    assert c2.delivered.get("already_sent") is True
    assert c2.delivered.get("sent") is not True


@pytest.mark.asyncio
async def test_back_to_back_confirms_without_restage_send_once(monkeypatch):
    """Two confirms against one stage (no re-prepare) still send at most once."""
    sends = []
    monkeypatch.setattr(sms_tool, "send_sms",
                        lambda phone, msg: sends.append((phone, msg)) or asyncio.sleep(0))

    await _stage("dinner at 7")
    c1 = FakeParams({})
    await sms_tool.handle_confirm_send_text(c1)
    c2 = FakeParams({})
    await sms_tool.handle_confirm_send_text(c2)

    assert len(sends) == 1


@pytest.mark.asyncio
async def test_new_message_still_sends_normally(monkeypatch):
    """A genuinely DIFFERENT prepare->confirm cycle must still send."""
    sends = []

    async def fake_send(phone, message):
        sends.append((phone, message))

    monkeypatch.setattr(sms_tool, "send_sms", fake_send)

    await _stage("first message")
    c1 = FakeParams({})
    await sms_tool.handle_confirm_send_text(c1)

    await _stage("a totally different message")
    c2 = FakeParams({})
    await sms_tool.handle_confirm_send_text(c2)

    assert len(sends) == 2
    assert c2.delivered["ok"] is True and c2.delivered.get("sent") is True
