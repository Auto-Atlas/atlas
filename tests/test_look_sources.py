# Tests for the source-agnostic look path (goal 2026-07-05-glasses-integration):
#   - the surface-aware hub (/v1/stream?surface=phone|glasses) and per-surface listener
#     counts on /v1/vision/request + /v1/visual/announce,
#   - VisionRequest.source validation,
#   - the look tool's per-source failure honesty (phone / glasses / auto) and happy path.
# Style mirrors test_vision_phone.py (TestClient API tests + monkeypatched tool tests).
import asyncio
import importlib

import pytest
from fastapi.testclient import TestClient

TOKEN = "secret-token-1234567890123456"


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
    return TestClient(approval_api.app), approval_api


def auth():
    return {"Authorization": f"Bearer {TOKEN}"}


# ---- surface-aware hub -------------------------------------------------------

def test_stream_rejects_unknown_surface(api):
    from starlette.websockets import WebSocketDisconnect
    client, _ = api
    with client:
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/v1/stream?surface=telescope",
                                          subprotocols=["bearer", TOKEN]):
                pass


def test_glasses_client_counts_as_glasses_surface(api):
    client, _ = api
    with client:
        with client.websocket_connect("/v1/stream?surface=glasses",
                                      subprotocols=["bearer", TOKEN]):
            # any-source request sees the one connected camera...
            r = client.post("/v1/vision/request",
                            json={"request_id": "a" * 16, "source": "any"}, headers=auth())
            assert r.json() == {"ok": True, "listeners": 1}
            # ...a glasses-source request sees it...
            r = client.post("/v1/vision/request",
                            json={"request_id": "a" * 16, "source": "glasses"}, headers=auth())
            assert r.json()["listeners"] == 1
            # ...but a phone-source request sees ZERO (wrong surface connected).
            r = client.post("/v1/vision/request",
                            json={"request_id": "a" * 16, "source": "phone"}, headers=auth())
            assert r.json()["listeners"] == 0


def test_default_surface_is_phone(api):
    client, _ = api
    with client:
        # No ?surface -> the existing app's behavior: counted as phone.
        with client.websocket_connect("/v1/stream", subprotocols=["bearer", TOKEN]):
            r = client.post("/v1/vision/request",
                            json={"request_id": "b" * 16, "source": "phone"}, headers=auth())
            assert r.json()["listeners"] == 1
            r = client.post("/v1/vision/request",
                            json={"request_id": "b" * 16, "source": "glasses"}, headers=auth())
            assert r.json()["listeners"] == 0


def test_vision_request_rejects_bad_source(api):
    client, _ = api
    with client:
        r = client.post("/v1/vision/request",
                        json={"request_id": "c" * 16, "source": "webcam"}, headers=auth())
        assert r.status_code == 400
        # source defaults to "any" and is optional (back-compat with the phone app).
        r = client.post("/v1/vision/request",
                        json={"request_id": "c" * 16}, headers=auth())
        assert r.status_code == 200 and r.json()["listeners"] == 0


def test_visual_announce_reports_per_surface_counts(api):
    client, _ = api
    with client:
        with client.websocket_connect("/v1/stream?surface=glasses",
                                      subprotocols=["bearer", TOKEN]):
            r = client.post("/v1/visual/announce",
                            json={"kind": "card", "title": "hi"}, headers=auth())
            body = r.json()
            assert body["ok"] is True
            assert body["listeners"] == 1                     # additive total, unchanged key
            assert body["surfaces"] == {"phone": 0, "glasses": 1}


# ---- look tool: per-source failure honesty + happy path ----------------------

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


def test_auto_no_camera_names_both_devices(vt, monkeypatch):
    tool, _ = vt

    async def fake_request(request_id, prompt, source="any"):
        assert source == "any"          # "auto" maps to "any"
        return 0

    monkeypatch.setattr(tool, "_request_capture", fake_request)
    p = _Params({"prompt": "what is this"})          # no source -> auto
    asyncio.run(tool.handle_look(p))
    res = p.results[0]
    assert res["ok"] is False
    inst = res["instruction"].lower()
    assert "phone" in inst and "glasses" in inst


def test_glasses_source_no_listener_says_glasses(vt, monkeypatch):
    tool, _ = vt

    async def fake_request(request_id, prompt, source="any"):
        assert source == "glasses"
        return 0

    monkeypatch.setattr(tool, "_request_capture", fake_request)
    p = _Params({"prompt": "read this", "source": "glasses"})
    asyncio.run(tool.handle_look(p))
    res = p.results[0]
    assert res["ok"] is False and "glasses" in res["instruction"].lower()


def test_phone_source_no_listener_says_open_the_app(vt, monkeypatch):
    tool, _ = vt

    async def fake_request(request_id, prompt, source="any"):
        assert source == "phone"
        return 0

    monkeypatch.setattr(tool, "_request_capture", fake_request)
    p = _Params({"prompt": "what is this", "source": "phone"})
    asyncio.run(tool.handle_look(p))
    res = p.results[0]
    assert res["ok"] is False and "open" in res["instruction"].lower()


def test_look_via_phone_wrapper_pins_phone_source(vt, monkeypatch):
    tool, _ = vt
    seen = {}

    async def fake_request(request_id, prompt, source="any"):
        seen["source"] = source
        return 0

    monkeypatch.setattr(tool, "_request_capture", fake_request)
    p = _Params({"prompt": "what is this"})
    asyncio.run(tool.handle_look_via_phone(p))
    assert seen["source"] == "phone"


def test_look_happy_path_describes_frame(vt, monkeypatch):
    tool, vf = vt

    async def fake_request(request_id, prompt, source="any"):
        vf.save(request_id, b"\xff\xd8jpeg")     # camera answers mid-wait
        return 1

    async def fake_describe(jpeg, prompt):
        assert jpeg == b"\xff\xd8jpeg"
        return "a workbench with a red toolbox"

    monkeypatch.setattr(tool, "_request_capture", fake_request)
    monkeypatch.setattr(tool, "_describe", fake_describe)
    p = _Params({"prompt": "what tool is this", "source": "glasses"})
    asyncio.run(tool.handle_look(p))
    res = p.results[0]
    assert res["ok"] is True
    assert res["description"] == "a workbench with a red toolbox"
