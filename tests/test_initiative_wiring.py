# The engine flag flips ownership of calendar surfacing: engine on -> calendar_watch
# callers stand down (no double delivery); engine off -> legacy watcher unaffected.
import initiative


def test_flag_flips_ownership(monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE", "1")
    assert initiative.enabled() is True
    monkeypatch.setenv("EVE_INITIATIVE", "0")
    assert initiative.enabled() is False


def test_bot_calendar_watcher_stands_down_when_engine_on():
    # Source-level check (bot.py builds the pipeline; importing it needs hardware):
    # the stand-down guard must exist in calendar_watcher and initiative_watcher must
    # be created. This is a wiring tripwire, not a behavior test.
    src = open("bot.py").read()
    assert "initiative.enabled()" in src
    assert "initiative_watcher" in src
    assert "initiative_task" in src
