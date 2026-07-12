from dataclasses import dataclass, field
from typing import Callable, Optional
import pytest
import tool_policy
import speaker_state
from tool_policy import ToolPolicy, policy, tier_allows


# Define FakeParams locally — there is no tests/__init__.py, so `tests` is not an
# importable package; the codebase pattern is a per-file FakeParams (see
# tests/test_channel_knowledge_tools.py). Mirrors tests/test_tool_policy.py exactly.
@dataclass
class FakeParams:
    arguments: dict
    context: object = None
    delivered: object = None
    last_kwargs: dict = field(default_factory=dict)
    result_callback: Optional[Callable] = None
    def __post_init__(self):
        if self.result_callback is None:
            async def _capture(result, **kwargs):
                self.delivered = result
                self.last_kwargs = kwargs
            self.result_callback = _capture


def setup_function():
    speaker_state.reset()
    tool_policy._staged.clear()
    tool_policy._injected.clear()


# ---- pure gate logic ----
def test_owner_allows_everything():
    assert tier_allows("create_invoice", "high", "owner") is True
    assert tier_allows("jarvis_agent", "medium", "owner") is True


def test_known_blocks_high_and_owner_only():
    assert tier_allows("get_weather", "low", "known") is True
    assert tier_allows("remember", "medium", "known") is True
    assert tier_allows("create_invoice", "high", "known") is False
    assert tier_allows("jarvis_agent", "medium", "known") is False   # OWNER_ONLY
    assert tier_allows("open_on_pc", "low", "known") is False        # OWNER_ONLY


def test_kid_low_only_and_no_private_reads():
    assert tier_allows("get_weather", "low", "kid") is True
    assert tier_allows("search_knowledge", "low", "kid") is True
    assert tier_allows("check_email", "low", "kid") is False         # KID_DENY
    assert tier_allows("get_calendar", "low", "kid") is False        # KID_DENY
    assert tier_allows("remember", "medium", "kid") is False         # above cap


def test_unknown_denies_all():
    assert tier_allows("get_weather", "low", "unknown") is False


def test_failclosed_on_garbage():
    assert tier_allows("get_weather", "low", "bogus_tier") is False  # unknown tier name
    assert tier_allows("mystery", "weird", "known") is False         # garbage risk -> 99


# ---- wrapper integration ----
async def _ok(params):
    await params.result_callback({"ok": True, "ran": True})


@pytest.mark.asyncio
async def test_wrapper_denies_when_no_speaker_set():
    # boot default: nobody identified -> deny (fail-closed)
    spec = ToolPolicy(risk_level="high")
    wrapped = policy("create_invoice", spec, _ok)
    p = FakeParams({"customer": {"name": "X"}, "line_items": [1]})
    await wrapped(p)
    assert p.delivered["denied"] is True


@pytest.mark.asyncio
async def test_wrapper_allows_owner_low_risk():
    speaker_state.set_current("Owner", "owner", 0.9)
    spec = ToolPolicy(risk_level="low")
    wrapped = policy("get_weather", spec, _ok)
    p = FakeParams({})
    await wrapped(p)
    assert p.delivered == {"ok": True, "ran": True}


@pytest.mark.asyncio
async def test_known_voice_cannot_confirm_owner_prepared_draft():
    # Owner prepares a high-risk draft (single-tool flow)...
    tool_policy._staged.clear()
    speaker_state.set_current("Owner", "owner", 0.95)
    spec = ToolPolicy(needs_confirmation=True, risk_level="high")
    wrapped = policy("create_invoice", spec, _ok)
    ctx = object()
    prepare = FakeParams({"customer": {"name": "X"}, "line_items": [1]}, context=ctx)
    await wrapped(prepare)
    assert prepare.delivered["needs_confirmation"] is True
    # ...then Alex (known) re-calls the SAME tool with confirmed=true.
    speaker_state.set_current("Alex", "known", 0.9)
    confirm = FakeParams({"customer": {"name": "X"}, "line_items": [1], "confirmed": True}, context=ctx)
    await wrapped(confirm)
    # The top-of-wrapped tier gate denies the high-risk re-call for a known speaker:
    assert confirm.delivered["denied"] is True
    assert "ran" not in (confirm.delivered or {})        # the frozen draft did NOT fire
    assert tool_policy._staged.get(id(ctx)) is not None   # still parked, awaiting owner
