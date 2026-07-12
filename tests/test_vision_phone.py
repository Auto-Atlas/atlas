# Tests for phone-camera vision: the vision_frames spool (transient, hostile-id
# safe), the approval_api /v1/vision endpoints (auth, validation, size cap), and
# the look_via_phone tool's leg-by-leg failure honesty.
import asyncio
import base64
import importlib
import time

import pytest
from fastapi.testclient import TestClient

TOKEN = "secret-token-1234567890123456"


@pytest.fixture
def vf(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_VISION_SPOOL", str(tmp_path / "frames"))
    import vision_frames
    importlib.reload(vision_frames)
    return vision_frames


@pytest.fixture
def api(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_VISION_SPOOL", str(tmp_path / "frames"))
    monkeypatch.setenv("EVE_APPROVAL_DB", str(tmp_path / "a.db"))
    monkeypatch.setenv("EVE_APP_TOKEN", TOKEN)
    monkeypatch.setenv("JARVIS_MEMORY_PAGE", str(tmp_path / "jarvis-memory.md"))
    monkeypatch.setenv("JARVIS_LOG_DIR", str(tmp_path / "transcripts"))
    import vision_frames
    importlib.reload(vision_frames)
    import approval_store
    importlib.reload(approval_store)
    import approval_api
    importlib.reload(approval_api)
    return TestClient(approval_api.app), vision_frames


def auth():
    return {"Authorization": f"Bearer {TOKEN}"}


# ---- spool -------------------------------------------------------------------

def test_spool_take_is_read_once(vf):
    vf.save("a" * 16, b"jpegbytes")
    assert vf.take("a" * 16) == b"jpegbytes"
    assert vf.take("a" * 16) is None, "frames are transient: take() deletes on read"


def test_spool_rejects_hostile_ids(vf):
    for bad in ("../../etc/passwd", "abc/def", "ABCDEF1234567890", "", "short", "a" * 40):
        assert not vf.valid_id(bad)
        assert vf.take(bad) is None
    with pytest.raises(ValueError):
        vf.save("../escape", b"x")


def test_spool_sweep_drops_stale(vf):
    import os
    p = vf.save("b" * 16, b"old")
    os.utime(p, (time.time() - 999, time.time() - 999))
    vf.save("c" * 16, b"fresh")
    assert vf.sweep(max_age_s=300) == 1
    assert vf.take("b" * 16) is None
    assert vf.take("c" * 16) == b"fresh"


# ---- API endpoints -----------------------------------------------------------

def test_vision_request_requires_auth_and_valid_id(api):
    client, _ = api
    with client:
        r = client.post("/v1/vision/request", json={"request_id": "a" * 16})
        assert r.status_code in (401, 403)
        r = client.post("/v1/vision/request", json={"request_id": "../bad"}, headers=auth())
        assert r.status_code == 400
        r = client.post("/v1/vision/request", json={"request_id": "a" * 16}, headers=auth())
        assert r.status_code == 200
        # No app websocket connected in this test -> the tool can fail fast.
        assert r.json() == {"ok": True, "listeners": 0}


def test_vision_frame_round_trip_and_validation(api):
    client, vf = api
    with client:
        good = base64.b64encode(b"\xff\xd8fakejpeg").decode()
        r = client.post("/v1/vision/frame",
                        json={"request_id": "d" * 16, "jpeg_b64": good}, headers=auth())
        assert r.status_code == 200 and r.json()["bytes"] == len(b"\xff\xd8fakejpeg")
        assert vf.take("d" * 16) == b"\xff\xd8fakejpeg"
        # bad base64 / bad id / empty are rejected, size cap enforced
        r = client.post("/v1/vision/frame",
                        json={"request_id": "d" * 16, "jpeg_b64": "!!!"}, headers=auth())
        assert r.status_code == 400
        r = client.post("/v1/vision/frame",
                        json={"request_id": "../x", "jpeg_b64": good}, headers=auth())
        assert r.status_code == 400
        r = client.post("/v1/vision/frame",
                        json={"request_id": "e" * 16, "jpeg_b64": ""}, headers=auth())
        assert r.status_code == 400
        import approval_api
        too_big = base64.b64encode(b"x" * (approval_api._VISION_MAX_BYTES + 1)).decode()
        r = client.post("/v1/vision/frame",
                        json={"request_id": "e" * 16, "jpeg_b64": too_big}, headers=auth())
        assert r.status_code == 413


def test_any_authed_client_can_answer_a_look_request(api):
    """GLASSES-READINESS CONTRACT (goal 2026-07-05-glasses-integration): the vision
    round trip is deliberately client-agnostic. capture_frame broadcasts to EVERY
    /v1/stream subscriber, and /v1/vision/frame accepts the answer from ANY client
    holding the bearer — a MentraOS bridge (or Meta companion) can give EVE eyes
    with ZERO server changes. This test pins that: a 'different device' (plain
    authed HTTP client, not the phone app) fulfills a look request end-to-end."""
    client, vf = api
    with client:
        rid = "9" * 16
        # EVE asks (what look_via_phone's _request_capture does)...
        r = client.post("/v1/vision/request",
                        json={"request_id": rid, "prompt": "what is this"}, headers=auth())
        assert r.status_code == 200
        # ...and a NON-PHONE client answers with the frame.
        glasses_jpeg = base64.b64encode(b"\xff\xd8glassesframe").decode()
        r = client.post("/v1/vision/frame",
                        json={"request_id": rid, "jpeg_b64": glasses_jpeg}, headers=auth())
        assert r.status_code == 200
        # The tool's poll loop would now pick it up from the spool.
        assert vf.take(rid) == b"\xff\xd8glassesframe"


# ---- look_via_phone tool -----------------------------------------------------

class _Params:
    def __init__(self, arguments):
        self.arguments = arguments
        self.results = []

    async def result_callback(self, result):
        self.results.append(result)


@pytest.fixture
def vt(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_VISION_SPOOL", str(tmp_path / "frames"))
    monkeypatch.setenv("EVE_VISION_WAIT_S", "1")
    import vision_frames
    importlib.reload(vision_frames)
    import vision_tool
    importlib.reload(vision_tool)
    return vision_tool, vision_frames


def test_no_listeners_fails_fast_with_open_the_app(vt, monkeypatch):
    tool, _ = vt

    async def fake_request(request_id, prompt, source="any"):
        return 0

    monkeypatch.setattr(tool, "_request_capture", fake_request)
    p = _Params({"prompt": "what is this"})
    asyncio.run(tool.handle_look_via_phone(p))
    res = p.results[0]
    assert res["ok"] is False
    assert "open" in res["instruction"].lower()


def test_no_frame_timeout_names_the_phone_leg(vt, monkeypatch):
    tool, _ = vt

    async def fake_request(request_id, prompt, source="any"):
        return 1

    monkeypatch.setattr(tool, "_request_capture", fake_request)
    p = _Params({})
    asyncio.run(tool.handle_look_via_phone(p))
    res = p.results[0]
    assert res["ok"] is False and "frame" in res["error"]


def test_happy_path_describes_the_delivered_frame(vt, monkeypatch):
    tool, vf = vt
    captured = {}

    async def fake_request(request_id, prompt, source="any"):
        # Simulate the app answering: drop the frame in the spool mid-wait.
        vf.save(request_id, b"\xff\xd8jpeg")
        captured["prompt"] = prompt
        return 1

    async def fake_describe(jpeg, prompt):
        assert jpeg == b"\xff\xd8jpeg"
        return "a red toolbox on a workbench"

    monkeypatch.setattr(tool, "_request_capture", fake_request)
    monkeypatch.setattr(tool, "_describe", fake_describe)
    p = _Params({"prompt": "what tool is this"})
    asyncio.run(tool.handle_look_via_phone(p))
    res = p.results[0]
    assert res["ok"] is True
    assert res["description"] == "a red toolbox on a workbench"
    assert captured["prompt"] == "what tool is this"


def test_vlm_failure_says_picture_arrived_but_model_failed(vt, monkeypatch):
    tool, vf = vt

    async def fake_request(request_id, prompt, source="any"):
        vf.save(request_id, b"\xff\xd8jpeg")
        return 1

    async def fake_describe(jpeg, prompt):
        raise RuntimeError("vision model error: connection refused")

    monkeypatch.setattr(tool, "_request_capture", fake_request)
    monkeypatch.setattr(tool, "_describe", fake_describe)
    p = _Params({})
    asyncio.run(tool.handle_look_via_phone(p))
    res = p.results[0]
    assert res["ok"] is False
    assert "vision model" in res["error"]
