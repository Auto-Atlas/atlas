import hand_tool


def test_unknown_pose_rejected():
    out = hand_tool.actuate("karate-chop")
    assert out["ok"] is False and "pose" in out["error"].lower()


def test_known_pose_invokes_bridge(monkeypatch):
    calls = {}

    def fake_run(argv, timeout):
        calls["argv"] = argv
        return (0, "moved", "")

    monkeypatch.setattr(hand_tool, "_run_cli", fake_run)
    out = hand_tool.actuate("open", hand="right")
    assert out["ok"] is True
    assert "eve_pose.py" in " ".join(calls["argv"])
    assert "open" in calls["argv"] and "right" in calls["argv"]


def test_bridge_failure_reported_honestly(monkeypatch):
    def fake_run(argv, timeout):
        return (124, "", "timeout after 20s")

    monkeypatch.setattr(hand_tool, "_run_cli", fake_run)
    out = hand_tool.actuate("close")
    assert out["ok"] is False and "failed" in out["error"].lower()
