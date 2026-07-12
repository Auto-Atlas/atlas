# Tests for glasses_stream — the continuous-vision service. Covers the pure pieces
# (iter_jpegs framing, Throttle) exhaustively, plus a real end-to-end integration that
# pushes a synthetic RTMP stream through the service's ffmpeg listener and asserts the
# (mocked) VLM + narrate path fired.
import asyncio
import shutil
import socket
from unittest.mock import AsyncMock

import pytest

import glasses_stream
from glasses_link import GlassesConfig


# ---- iter_jpegs framing ------------------------------------------------------
def _fake_jpeg(payload: bytes) -> bytes:
    # SOI ... payload ... EOI (payload chosen to contain no stray markers).
    return b"\xff\xd8" + b"\x00\x11\x22" + payload + b"\xff\xd9"


def test_iter_jpegs_splits_two_concatenated_frames():
    a = _fake_jpeg(b"AAAA")
    b = _fake_jpeg(b"BBBB")
    out = list(glasses_stream.iter_jpegs([a + b]))
    assert out == [a, b]


def test_iter_jpegs_handles_partial_chunks_across_reads():
    a = _fake_jpeg(b"HELLO")
    b = _fake_jpeg(b"WORLD")
    stream = a + b
    # Feed the concatenated bytes one byte at a time — worst-case fragmentation, incl. a
    # split SOI/EOI marker. Every frame must still emerge intact and in order.
    chunks = [stream[i:i + 1] for i in range(len(stream))]
    out = list(glasses_stream.iter_jpegs(chunks))
    assert out == [a, b]


def test_iter_jpegs_drops_junk_before_first_soi():
    a = _fake_jpeg(b"REAL")
    out = list(glasses_stream.iter_jpegs([b"garbage-preamble-no-marker" + a]))
    assert out == [a]


def test_iter_jpegs_buffers_incomplete_trailing_frame():
    a = _fake_jpeg(b"DONE")
    partial = b"\xff\xd8\x00\x00still-going"     # SOI but no EOI yet
    out = list(glasses_stream.iter_jpegs([a + partial]))
    assert out == [a]                             # only the complete frame; partial withheld


# ---- Throttle ----------------------------------------------------------------
def test_throttle_respects_min_interval():
    t = glasses_stream.Throttle(min_interval_s=8.0)
    assert t.allow("a bright red toolbox", now=100.0) is True
    # A clearly different scene, but too soon -> suppressed.
    assert t.allow("a blue coffee mug on a desk", now=104.0) is False
    # After the interval, the changed scene narrates.
    assert t.allow("a blue coffee mug on a desk", now=109.0) is True


def test_throttle_suppresses_near_duplicate_text():
    t = glasses_stream.Throttle(min_interval_s=1.0)
    assert t.allow("a red toolbox on a workbench", now=0.0) is True
    # Interval elapsed, but the description is essentially the same -> skip.
    assert t.allow("A red toolbox on a workbench.", now=50.0) is False


def test_throttle_suppressed_duplicate_does_not_reset_clock():
    t = glasses_stream.Throttle(min_interval_s=10.0)
    assert t.allow("scene one description here", now=0.0) is True
    # Near-duplicate at t=20 is suppressed and must NOT push the clock forward...
    assert t.allow("scene one description here!", now=20.0) is False
    # ...so a genuinely new scene right after still narrates (interval since the last
    # SPOKEN line, t=0, has long passed).
    assert t.allow("a totally different kitchen scene", now=21.0) is True


# ---- Integration: real ffmpeg RTMP round trip --------------------------------
def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


@pytest.mark.skipif(not shutil.which("/usr/bin/ffmpeg") and not shutil.which("ffmpeg"),
                    reason="ffmpeg not available")
async def test_rtmp_stream_drives_describe_and_narrate():
    port = _free_port()
    cfg = GlassesConfig(
        enabled=True, rtmp_port=port, rtmp_app="eve",
        sample_fps=5.0, narrate_min_s=0.0, narrate_url="",
        vlm_url="http://127.0.0.1:8093", vlm_model="qwen3-vl",
    )
    describe = AsyncMock(return_value="a synthetic test pattern")
    narrate = AsyncMock()
    stop_event = asyncio.Event()

    service = asyncio.ensure_future(
        glasses_stream.run(cfg, describe=describe, narrate=narrate, stop_event=stop_event))
    # Let the ffmpeg listener come up before publishing.
    await asyncio.sleep(1.5)

    pub = await asyncio.create_subprocess_exec(
        glasses_stream.ffmpeg_bin(), "-hide_banner", "-loglevel", "error",
        "-re", "-f", "lavfi", "-i", "testsrc=duration=3:size=320x240:rate=15",
        "-c:v", "flv", "-f", "flv", f"rtmp://127.0.0.1:{port}/eve",
    )

    try:
        # Wait (bounded) for at least one frame to reach the mocked VLM + narrate path.
        deadline = asyncio.get_event_loop().time() + 16
        while asyncio.get_event_loop().time() < deadline:
            if describe.await_count >= 1 and narrate.await_count >= 1:
                break
            await asyncio.sleep(0.25)
    finally:
        stop_event.set()
        if pub.returncode is None:
            pub.terminate()
        try:
            await asyncio.wait_for(pub.wait(), timeout=5)
        except asyncio.TimeoutError:
            pub.kill()
        service.cancel()
        try:
            await asyncio.wait_for(service, timeout=5)
        except (asyncio.CancelledError, asyncio.TimeoutError):
            pass

    assert describe.await_count >= 1, "no JPEG frames were parsed off the RTMP stream"
    assert narrate.await_count >= 1, "the narrate path never fired"
    # Narration carries the VLM's description text.
    assert narrate.await_args.args[0] == "a synthetic test pattern"
