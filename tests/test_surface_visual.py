# Tests for the visual surfacing bus: visual_store (TTL'd, non-consuming reads,
# hostile-id safe), the /v1/visual endpoints (serve + announce broadcast shape),
# and the surface_visual tool's kinds + failure honesty.
import asyncio
import importlib
import io
import time

import pytest
from fastapi.testclient import TestClient

TOKEN = "secret-token-1234567890123456"


def _jpeg_bytes(w=64, h=48, color=(200, 30, 30)) -> bytes:
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (w, h), color).save(buf, "JPEG")
    return buf.getvalue()


@pytest.fixture
def vs(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_VISUAL_SPOOL", str(tmp_path / "visuals"))
    import visual_store
    importlib.reload(visual_store)
    return visual_store


@pytest.fixture
def api(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_VISUAL_SPOOL", str(tmp_path / "visuals"))
    monkeypatch.setenv("EVE_APPROVAL_DB", str(tmp_path / "a.db"))
    monkeypatch.setenv("EVE_APP_TOKEN", TOKEN)
    monkeypatch.setenv("JARVIS_MEMORY_PAGE", str(tmp_path / "jarvis-memory.md"))
    monkeypatch.setenv("JARVIS_LOG_DIR", str(tmp_path / "transcripts"))
    import visual_store
    importlib.reload(visual_store)
    import approval_store
    importlib.reload(approval_store)
    import approval_api
    importlib.reload(approval_api)
    return TestClient(approval_api.app), visual_store


def auth():
    return {"Authorization": f"Bearer {TOKEN}"}


# ---- store -------------------------------------------------------------------

def test_store_reads_do_not_consume(vs):
    vid = vs.save(b"jpegdata")
    assert vs.valid_id(vid)
    assert vs.read(vid) == b"jpegdata"
    assert vs.read(vid) == b"jpegdata", "surfaced visuals are re-fetchable"


def test_store_rejects_hostile_ids_and_sweeps(vs):
    import os
    assert vs.read("../../etc/passwd") is None
    assert not vs.valid_id("ABC/../x")
    vid = vs.save(b"old")
    p = vs.spool_dir() / f"{vid}.jpg"
    os.utime(p, (time.time() - 9999, time.time() - 9999))
    assert vs.sweep(max_age_s=3600) == 1
    assert vs.read(vid) is None


# ---- API ---------------------------------------------------------------------

def test_visual_serve_and_announce(api):
    client, vs = api
    with client:
        vid = vs.save(_jpeg_bytes())
        # unauthenticated fetch refused
        assert client.get(f"/v1/visual/{vid}").status_code in (401, 403)
        r = client.get(f"/v1/visual/{vid}", headers=auth())
        assert r.status_code == 200
        assert r.headers["content-type"] == "image/jpeg"
        assert r.content[:2] == b"\xff\xd8"  # JPEG magic
        # unknown/hostile ids
        assert client.get("/v1/visual/" + "f" * 16, headers=auth()).status_code == 404
        # encoded traversal: router 404s it before the handler; plain bad id 400s
        assert client.get("/v1/visual/..%2Fx", headers=auth()).status_code in (400, 404)
        assert client.get("/v1/visual/NOTHEX!", headers=auth()).status_code == 400
        # announce validates the id and reports listeners (0 in tests)
        r = client.post("/v1/visual/announce", headers=auth(),
                        json={"kind": "note", "title": "hi", "text": "line1"})
        assert r.status_code == 200 and r.json()["listeners"] == 0
        r = client.post("/v1/visual/announce", headers=auth(),
                        json={"kind": "image", "visual_id": "../bad"})
        assert r.status_code == 400


# ---- tool --------------------------------------------------------------------

class _Params:
    def __init__(self, arguments):
        self.arguments = arguments
        self.results = []

    async def result_callback(self, result):
        self.results.append(result)


class _Bridge:
    def __init__(self):
        self.events = []

    async def broadcast(self, event):
        self.events.append(event)


@pytest.fixture
def vt(tmp_path, monkeypatch):
    monkeypatch.setenv("EVE_VISUAL_SPOOL", str(tmp_path / "visuals"))
    import visual_store
    importlib.reload(visual_store)
    import visual_tool
    importlib.reload(visual_tool)
    return visual_tool, visual_store


def test_note_kind_surfaces_text_to_both_surfaces(vt, monkeypatch):
    tool, _ = vt
    announced = []

    async def fake_announce(payload):
        announced.append(payload)

    monkeypatch.setattr(tool, "_announce", fake_announce)
    bridge = _Bridge()
    handler = tool.make_surface_visual_handler(bridge)
    p = _Params({"kind": "note", "title": "Build log", "text": "error on line 3"})
    asyncio.run(handler(p))
    res = p.results[0]
    assert res["ok"] is True
    assert set(res["shown_on"]) == {"phone app", "desktop stage"}
    assert announced[0]["text"] == "error on line 3"
    assert bridge.events[0]["type"] == "surface_visual"


def test_image_bridge_copy_carries_inline_data_uri(vt, tmp_path, monkeypatch):
    # The desktop stage has no approval-api bearer: its loopback-WS copy embeds
    # the bounded JPEG as a data URI; the phone copy stays URL-only.
    tool, _ = vt
    src = tmp_path / "pic.png"
    from PIL import Image
    Image.new("RGB", (100, 80), (5, 5, 250)).save(src, "PNG")

    async def fake_announce(payload):
        assert "data_uri" not in payload

    monkeypatch.setattr(tool, "_announce", fake_announce)
    bridge = _Bridge()
    handler = tool.make_surface_visual_handler(bridge)
    p = _Params({"kind": "image", "path": str(src)})
    asyncio.run(handler(p))
    assert p.results[0]["ok"] is True
    assert bridge.events[0]["data_uri"].startswith("data:image/jpeg;base64,")


def test_image_kind_stores_shrunk_jpeg_with_url(vt, tmp_path, monkeypatch):
    tool, vs = vt
    src = tmp_path / "pic.png"
    from PIL import Image
    Image.new("RGB", (2400, 1200), (10, 200, 10)).save(src, "PNG")
    announced = []

    async def fake_announce(payload):
        announced.append(payload)

    monkeypatch.setattr(tool, "_announce", fake_announce)
    handler = tool.make_surface_visual_handler(None)
    p = _Params({"kind": "image", "path": str(src)})
    asyncio.run(handler(p))
    assert p.results[0]["ok"] is True
    ev = announced[0]
    assert ev["url"] == f"/v1/visual/{ev['visual_id']}"
    assert "data_uri" not in ev, "phone copy fetches by URL, never carries the blob"
    data = vs.read(ev["visual_id"])
    from PIL import Image as I
    img = I.open(io.BytesIO(data))
    assert img.format == "JPEG" and max(img.size) <= 1600, "normalized + bounded"


def test_missing_image_and_bad_kind_fail_honestly(vt, monkeypatch):
    tool, _ = vt

    async def fake_announce(payload):  # must never be reached
        raise AssertionError("announced a failed visual")

    monkeypatch.setattr(tool, "_announce", fake_announce)
    handler = tool.make_surface_visual_handler(None)
    p = _Params({"kind": "image", "path": "/no/such/file.png"})
    asyncio.run(handler(p))
    assert p.results[0]["ok"] is False and "/no/such/file.png" in p.results[0]["error"]
    p2 = _Params({"kind": "hologram"})
    asyncio.run(handler(p2))
    assert p2.results[0]["ok"] is False
    p3 = _Params({"kind": "note"})  # note without text
    asyncio.run(handler(p3))
    assert p3.results[0]["ok"] is False


def test_no_surface_reachable_is_reported(vt, monkeypatch):
    tool, _ = vt

    async def failing_announce(payload):
        raise RuntimeError("api down")

    monkeypatch.setattr(tool, "_announce", failing_announce)
    handler = tool.make_surface_visual_handler(None)  # no bridge either
    p = _Params({"kind": "note", "text": "hello"})
    asyncio.run(handler(p))
    res = p.results[0]
    assert res["ok"] is False and "no surface" in res["error"]
