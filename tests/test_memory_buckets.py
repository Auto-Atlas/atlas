from dataclasses import dataclass
from typing import Callable, Optional
import pytest
import memory_tool
import speaker_state


# Local FakeParams (no tests/__init__.py — see Task 4 test note).
@dataclass
class FakeParams:
    arguments: dict
    context: object = None
    delivered: object = None
    result_callback: Optional[Callable] = None
    def __post_init__(self):
        if self.result_callback is None:
            async def _capture(result, **kwargs):
                self.delivered = result
            self.result_callback = _capture


def setup_function():
    speaker_state.reset()


def test_page_for_owner_is_main(monkeypatch, tmp_path):
    monkeypatch.setattr(memory_tool, "MEMORY_PAGE", tmp_path / "main.md")
    assert memory_tool._page_for("Owner", "owner") == tmp_path / "main.md"


def test_page_for_known_is_separate(monkeypatch, tmp_path):
    monkeypatch.setattr(memory_tool, "MEMORY_PAGE", tmp_path / "main.md")
    p = memory_tool._page_for("Alex", "known")
    assert p != (tmp_path / "main.md") and "alex" in p.name.lower()


@pytest.mark.asyncio
async def test_known_recall_does_not_return_owner_fact_on_a_hit(monkeypatch, tmp_path):
    monkeypatch.setattr(memory_tool, "MEMORY_PAGE", tmp_path / "main.md")
    # The owner remembers a private fact
    speaker_state.set_current("Owner", "owner", 1.0)
    await memory_tool.handle_remember(FakeParams({"fact": "the owner's bank PIN is 1234"}))
    # Alex recalls a query that WOULD match it
    speaker_state.set_current("Alex", "known", 0.9)
    p = FakeParams({"query": "bank PIN"})
    await memory_tool.handle_recall(p)
    assert p.delivered["matches"] == []     # bucket isolation holds on a hit
