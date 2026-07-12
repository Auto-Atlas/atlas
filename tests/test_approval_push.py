# Tests for approval_push — self-hosted ntfy wake + Telegram escalation.
# Real sockets, honest booleans. ntfy and OpenJarvis are both unreachable in the test env
# (dead port / not running), so notify() must return False/False WITHOUT raising.
import importlib

import pytest


@pytest.fixture
def push(monkeypatch):
    monkeypatch.setenv("EVE_NTFY_URL", "http://127.0.0.1:1")   # nothing listening
    monkeypatch.setenv("EVE_NTFY_TOPIC", "eve-approvals")
    monkeypatch.setenv("JARVIS_AGENT_URL", "http://127.0.0.1:1")  # OpenJarvis dead too
    import approval_push
    importlib.reload(approval_push)
    return approval_push


async def test_notify_returns_honest_false_when_everything_down(push):
    out = await push.notify("Alex — invoice The Browns, $1,200.00", "abc123")
    assert out["ntfy"] is False
    assert out["telegram"] is False        # escalation attempted, honestly failed


async def test_notify_never_raises(push):
    # even with a totally bogus URL, the caller (tool_policy) must never see an exception
    out = await push.notify("x", "id")
    assert isinstance(out, dict)
