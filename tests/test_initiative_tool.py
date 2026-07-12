# The feedback voice tool: persists via initiative.adjust, reports honestly on bad args,
# is owner-gated in tool_policy, and has a skill file so risk never silently downgrades.
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import initiative
import initiative_tool
import tool_policy


def _params(**arguments):
    return SimpleNamespace(arguments=arguments, result_callback=AsyncMock())


def test_adjust_persists_and_confirms(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "p.json"))
    p = _params(source="email", direction="mute")
    asyncio.run(initiative_tool.handle_adjust_surfacing(p))
    out = p.result_callback.await_args.args[0]
    assert out["ok"] is True and out["muted"] is True
    assert initiative.load_prefs()["sources"]["email"]["muted"] is True


def test_bad_direction_reports_error(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_PREFS", str(tmp_path / "p.json"))
    p = _params(source="email", direction="whatever")
    asyncio.run(initiative_tool.handle_adjust_surfacing(p))
    out = p.result_callback.await_args.args[0]
    assert out["ok"] is False and "direction" in out["error"]


def test_owner_gated_and_skill_present():
    assert "adjust_surfacing" in tool_policy.OWNER_ONLY
    assert tool_policy.tier_allows("adjust_surfacing", "low", "known") is False
    assert tool_policy.tier_allows("adjust_surfacing", "low", "owner") is True
    from skill_loader import load_skills
    sk = load_skills().get("adjust_surfacing")
    assert sk is not None, "skills/adjust_surfacing.md missing — risk would downgrade"


def test_schema_registered_in_jarvis_core():
    import jarvis_core
    names = [s.name for s in jarvis_core.ALL_TOOL_SCHEMAS]
    assert "adjust_surfacing" in names
