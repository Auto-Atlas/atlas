"""Tests for the two OpenJarvis-backed voice tools:
  * channel_tool.send_to_channel  — EXTERNAL ACTION, gated (requires_confirmation).
  * knowledge_tool.search_knowledge — READ, ungated.

Network is never touched: each test monkeypatches the OpenJarvisClient class in
the tool module with a fake whose async methods return canned data or raise.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import channel_tool  # noqa: E402
import knowledge_tool  # noqa: E402
from skill_loader import load_skills  # noqa: E402


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


# ---- send_to_channel --------------------------------------------------------

async def test_send_to_channel_success(monkeypatch):
    async def channel_send(self, channel, content, conversation_id=None):
        assert channel == "telegram"
        assert content == "on my way"
        return {"status": "sent", "channel": channel}

    monkeypatch.setattr(channel_tool, "OpenJarvisClient", _fake_client(channel_send=channel_send))
    p = FakeParams({"channel": "telegram", "message": "on my way"})
    await channel_tool.handle_send_to_channel(p)
    assert p.delivered["ok"] is True
    assert p.delivered["sent"] is True
    assert p.delivered["channel"] == "telegram"
    assert "instruction" in p.delivered


async def test_send_to_channel_missing_field(monkeypatch):
    # No client call should happen; missing message -> honest error.
    monkeypatch.setattr(channel_tool, "OpenJarvisClient", _fake_client())
    p = FakeParams({"channel": "telegram"})
    await channel_tool.handle_send_to_channel(p)
    assert p.delivered["ok"] is False
    assert "error" in p.delivered


async def test_send_to_channel_client_raises(monkeypatch):
    async def channel_send(self, channel, content, conversation_id=None):
        raise RuntimeError("daemon unreachable")

    monkeypatch.setattr(channel_tool, "OpenJarvisClient", _fake_client(channel_send=channel_send))
    p = FakeParams({"channel": "slack", "message": "hi"})
    await channel_tool.handle_send_to_channel(p)
    assert p.delivered["ok"] is False
    assert "slack" in p.delivered["error"]


# ---- search_knowledge -------------------------------------------------------

async def test_search_knowledge_success_and_truncation(monkeypatch):
    long_doc = "x" * 500

    async def memory_search(self, query, top_k=5):
        assert query == "pricing model"
        assert top_k == 5
        return [
            {"content": long_doc, "score": 0.91},
            {"content": "short note", "score": 0.42},
        ]

    monkeypatch.setattr(knowledge_tool, "OpenJarvisClient", _fake_client(memory_search=memory_search))
    p = FakeParams({"query": "pricing model"})
    await knowledge_tool.handle_search_knowledge(p)
    assert p.delivered["ok"] is True
    results = p.delivered["results"]
    assert len(results) == 2
    assert results[0]["score"] == 0.91
    assert len(results[0]["content"]) <= 200  # truncated
    assert results[1]["content"] == "short note"
    assert "instruction" in p.delivered


async def test_search_knowledge_empty_query(monkeypatch):
    monkeypatch.setattr(knowledge_tool, "OpenJarvisClient", _fake_client())
    p = FakeParams({"query": "   "})
    await knowledge_tool.handle_search_knowledge(p)
    assert p.delivered["ok"] is False
    assert "error" in p.delivered


async def test_search_knowledge_client_raises(monkeypatch):
    async def memory_search(self, query, top_k=5):
        raise RuntimeError("boom")

    monkeypatch.setattr(knowledge_tool, "OpenJarvisClient", _fake_client(memory_search=memory_search))
    p = FakeParams({"query": "anything"})
    await knowledge_tool.handle_search_knowledge(p)
    assert p.delivered["ok"] is False
    assert "knowledge search failed" in p.delivered["error"]


# ---- policy-gate routing ----------------------------------------------------

def test_skill_gating_routes_correctly():
    skills = load_skills()
    assert skills["send_to_channel"].requires_confirmation is True
    assert skills["search_knowledge"].requires_confirmation is False
