import sys


def test_oakd_unavailable_without_depthai(monkeypatch):
    # Force the ImportError path (depthai is not installed on x86).
    monkeypatch.setitem(sys.modules, "depthai", None)
    import importlib
    import oakd_vision
    importlib.reload(oakd_vision)
    cam = oakd_vision.OakDCamera()
    assert cam.available() is False
    out = cam.capture()
    assert out["ok"] is False and "unavailable" in out["error"].lower()
