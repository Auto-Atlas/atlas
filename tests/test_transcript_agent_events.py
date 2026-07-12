# Tests that agent talk-back events survive the transcript hop — the ONE artifact that
# crosses the process boundary from the voice loop (where a2a_fabric.handle_push runs) to
# approval_api's live forwarder (the app's /v1/stream). If TranscriptLogger drops them,
# the phone's Approvals live feed is blind no matter what the API does.
import json

import bridge


def _log_and_read(tmp_path, event):
    tl = bridge.TranscriptLogger(log_dir=str(tmp_path), tag="local")
    tl.log(event)
    files = list(tmp_path.glob("*.jsonl"))
    if not files:
        return None
    lines = files[0].read_text(encoding="utf-8").strip().splitlines()
    return json.loads(lines[-1]) if lines else None


def test_agent_talkback_events_are_logged(tmp_path):
    for kind in ("agent_progress", "agent_question", "agent_result", "agent_blocker",
                 "agent_task_assigned", "agent_task_cancelled", "agent_task_redirected"):
        got = _log_and_read(tmp_path, {"type": kind, "agent": "hermes",
                                       "task_id": "t1", "text": "step"})
        assert got is not None, f"{kind} was dropped by TranscriptLogger"
        assert got["type"] == kind and got["src"] == "local"


def test_unknown_types_still_dropped(tmp_path):
    # The logger stays a whitelist — adding agent events must not open the floodgates.
    assert _log_and_read(tmp_path, {"type": "definitely_not_a_thing", "x": 1}) is None
