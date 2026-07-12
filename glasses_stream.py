# glasses_stream.py — EVE's CONTINUOUS eyes through smart glasses.
#
# The on-demand `look` tool answers "what am I looking at" one frame at a time. This
# service is the other leg: the wearer's glasses (a MentraOS app, or a Meta companion
# track) push their point-of-view as an RTMP stream; EVE samples it at a low FPS,
# describes each frame on the LOCAL vision model, and — throttled — narrates a short
# line into the wearer's ear through the bridge's narrate webhook.
#
# Flow (three hops, no queue):
#   glasses --RTMP publish--> ffmpeg (-listen 1, this process spawns it)
#     --image2pipe MJPEG on stdout--> iter_jpegs() splits concatenated JPEGs
#     --keep ONLY the latest frame (drop stale; a slow VLM must never build a backlog)-->
#     vlm_client.describe() on :8093 --> Throttle (min interval + near-duplicate skip)
#     --> POST {"text": ...} to EVE_GLASSES_NARRATE_URL (empty => log-only)
#
# The frame never leaves the box (same privacy contract as on-demand vision). The
# service is persistent: when a stream ends ffmpeg exits and we loop back to listening.
# Clean shutdown on SIGTERM/SIGINT. All knobs come from glasses_link (product-safe
# defaults; nothing owner-specific). Runnable directly: `python glasses_stream.py`.
#
import asyncio
import difflib
import os
import re
import signal
import time
from contextlib import suppress

import aiohttp
from loguru import logger

import glasses_link
import vlm_client

_SOI = b"\xff\xd8"   # JPEG start-of-image
_EOI = b"\xff\xd9"   # JPEG end-of-image


# ---- Pure JPEG framing (unit-testable) --------------------------------------
class _JpegSplitter:
    """Incremental splitter: feed() raw stdout chunks, get back every COMPLETE JPEG
    (SOI..EOI) that has arrived. Bytes before the first SOI are junk and dropped; an
    incomplete trailing frame is buffered until the rest arrives (partial chunks across
    reads). ffmpeg's image2pipe emits whole JPEGs back-to-back, so SOI/EOI marker
    splitting is exact — 0xffd9 only appears as the real EOI (entropy data is byte-stuffed)."""

    def __init__(self):
        self._buf = bytearray()

    def feed(self, chunk: bytes) -> list[bytes]:
        frames: list[bytes] = []
        if chunk:
            self._buf.extend(chunk)
        while True:
            soi = self._buf.find(_SOI)
            if soi < 0:
                # No start marker yet. Keep a lone trailing 0xff (a split SOI marker),
                # else drop the junk so the buffer can't grow without bound.
                if self._buf and self._buf[-1] == 0xFF:
                    del self._buf[:-1]
                else:
                    self._buf.clear()
                break
            if soi > 0:
                del self._buf[:soi]        # drop junk before the frame
            eoi = self._buf.find(_EOI, 2)
            if eoi < 0:
                break                       # frame not complete yet — wait for more
            frames.append(bytes(self._buf[: eoi + 2]))
            del self._buf[: eoi + 2]
        return frames


def iter_jpegs(chunks):
    """Yield each complete JPEG from an iterable of byte chunks. Thin generator over
    _JpegSplitter so both the streaming service and tests share one framing impl."""
    sp = _JpegSplitter()
    for chunk in chunks:
        yield from sp.feed(chunk)


# ---- Narration throttle (pure) ----------------------------------------------
def _normalize(text: str) -> str:
    return re.sub(r"\s+", " ", (text or "").strip().lower())


class Throttle:
    """Decides whether a fresh description is worth speaking. Suppresses when (a) less
    than min_interval_s has passed since the last SPOKEN line, or (b) the new text is
    near-identical to the last spoken one. State updates ONLY when it returns True, so a
    suppressed near-duplicate doesn't reset the clock — the moment the scene actually
    changes (and the interval has elapsed) EVE speaks promptly. `now` is injectable so
    the class is deterministic and unit-testable."""

    def __init__(self, min_interval_s: float, dup_threshold: float = 0.9):
        self.min_interval_s = float(min_interval_s)
        self.dup_threshold = float(dup_threshold)
        self._last_at: float | None = None
        self._last_text: str | None = None

    def _similarity(self, a: str, b: str) -> float:
        return difflib.SequenceMatcher(None, _normalize(a), _normalize(b)).ratio()

    def allow(self, text: str, now: float | None = None) -> bool:
        now = time.monotonic() if now is None else now
        if self._last_at is not None and (now - self._last_at) < self.min_interval_s:
            return False
        if self._last_text is not None and self._similarity(text, self._last_text) >= self.dup_threshold:
            return False
        self._last_at = now
        self._last_text = text
        return True


# ---- Latest-frame slot (drop stale, never queue) ----------------------------
class _LatestFrame:
    """Single-slot handoff between the fast reader and the slow describe loop. Setting a
    new frame overwrites any unconsumed one — the VLM always works on the freshest view."""

    def __init__(self):
        self._frame: bytes | None = None
        self._event = asyncio.Event()

    def set(self, frame: bytes):
        self._frame = frame
        self._event.set()

    async def get(self, stop_event: asyncio.Event) -> bytes | None:
        """Wait for the next frame; return None if `stop_event` fires first (shutdown)."""
        waiter = asyncio.ensure_future(self._event.wait())
        stopper = asyncio.ensure_future(stop_event.wait())
        try:
            await asyncio.wait({waiter, stopper}, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for t in (waiter, stopper):
                if not t.done():
                    t.cancel()
                    with suppress(asyncio.CancelledError):
                        await t
        if stop_event.is_set():
            return None
        self._event.clear()
        return self._frame


# ---- ffmpeg RTMP listener ----------------------------------------------------
def ffmpeg_bin() -> str:
    return os.getenv("EVE_FFMPEG_BIN", "/usr/bin/ffmpeg")


def ffmpeg_listen_cmd(cfg) -> list[str]:
    """ffmpeg as an RTMP listener that decodes to a low-FPS MJPEG byte stream on stdout."""
    return [
        ffmpeg_bin(), "-hide_banner", "-loglevel", "error",
        "-listen", "1", "-i", cfg.rtmp_url,
        "-vf", f"fps={cfg.sample_fps}", "-q:v", "5",
        "-f", "image2pipe", "-c:v", "mjpeg", "-",
    ]


def _prompt() -> str:
    return os.getenv(
        "EVE_GLASSES_PROMPT",
        "Briefly describe what the wearer is looking at right now, in one short sentence.",
    )


def _make_describe(cfg):
    async def describe(frame: bytes) -> str:
        return await vlm_client.describe(frame, _prompt(), vlm_url=cfg.vlm_url, model=cfg.vlm_model)
    return describe


def _make_narrate(cfg):
    async def narrate(text: str) -> None:
        # Always log; POST to the bridge only when a narrate URL is configured.
        logger.info(f"glasses narrate: {text}")
        if not cfg.narrate_url:
            return
        timeout = aiohttp.ClientTimeout(total=8)
        try:
            async with aiohttp.ClientSession(timeout=timeout) as s:
                async with s.post(cfg.narrate_url, json={"text": text}) as r:
                    if r.status >= 400:
                        logger.warning(f"glasses narrate webhook HTTP {r.status}")
        except Exception as e:
            logger.warning(f"glasses narrate webhook failed: {e}")
    return narrate


# ---- Pipeline ---------------------------------------------------------------
async def _pump(proc, on_frame) -> None:
    """Read ffmpeg stdout, split JPEGs, hand each latest frame to on_frame. Returns when
    ffmpeg closes its stdout (the RTMP stream ended)."""
    sp = _JpegSplitter()
    while True:
        chunk = await proc.stdout.read(65536)
        if not chunk:
            return
        for frame in sp.feed(chunk):
            on_frame(frame)


async def _session(cfg, latest: _LatestFrame) -> None:
    """One listen-decode session: spawn ffmpeg, pump frames until the stream ends."""
    proc = await asyncio.create_subprocess_exec(
        *ffmpeg_listen_cmd(cfg),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
    )
    logger.info(f"glasses_stream: listening on {cfg.rtmp_url} (fps={cfg.sample_fps})")
    try:
        await _pump(proc, latest.set)
    finally:
        if proc.returncode is None:
            with suppress(ProcessLookupError):
                proc.terminate()
            with suppress(asyncio.TimeoutError):
                await asyncio.wait_for(proc.wait(), timeout=5)
            if proc.returncode is None:
                with suppress(ProcessLookupError):
                    proc.kill()


async def _describe_loop(latest, throttle, describe, narrate, stop_event) -> None:
    while not stop_event.is_set():
        frame = await latest.get(stop_event)
        if frame is None:
            return
        try:
            desc = await describe(frame)
        except Exception as e:
            logger.warning(f"glasses_stream: VLM describe failed: {e}")
            continue
        if not desc:
            continue
        if throttle.allow(desc):
            await narrate(desc)


async def run(cfg=None, *, describe=None, narrate=None, stop_event=None) -> None:
    """Persistent continuous-vision service. Spawns a fresh ffmpeg listener each session;
    when a stream ends, loops back to listening. `describe`/`narrate` are injectable for
    tests. Exits cleanly when `stop_event` is set (SIGTERM/SIGINT wire to it in main)."""
    cfg = cfg or glasses_link.load()
    describe = describe or _make_describe(cfg)
    narrate = narrate or _make_narrate(cfg)
    stop_event = stop_event or asyncio.Event()
    throttle = Throttle(cfg.narrate_min_s)
    latest = _LatestFrame()

    worker = asyncio.ensure_future(_describe_loop(latest, throttle, describe, narrate, stop_event))
    try:
        while not stop_event.is_set():
            session = asyncio.ensure_future(_session(cfg, latest))
            stopper = asyncio.ensure_future(stop_event.wait())
            await asyncio.wait({session, stopper}, return_when=asyncio.FIRST_COMPLETED)
            if stop_event.is_set():
                session.cancel()
                with suppress(asyncio.CancelledError):
                    await session
                break
            stopper.cancel()
            with suppress(asyncio.CancelledError):
                await stopper
            # session finished on its own (stream ended) -> loop back to listening
    finally:
        stop_event.set()
        worker.cancel()
        with suppress(asyncio.CancelledError):
            await worker


def main() -> None:
    cfg = glasses_link.load()
    if not cfg.enabled:
        logger.warning(
            "glasses_stream: EVE_GLASSES_ENABLED is off — continuous mode not started. "
            "Set EVE_GLASSES_ENABLED=1 (the systemd unit does) to run it."
        )
        return
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    stop_event = asyncio.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        with suppress(NotImplementedError):
            loop.add_signal_handler(sig, stop_event.set)
    logger.info(
        f"glasses_stream: starting (rtmp={cfg.rtmp_url} fps={cfg.sample_fps} "
        f"narrate_min_s={cfg.narrate_min_s} narrate_url={'set' if cfg.narrate_url else 'log-only'})"
    )
    try:
        loop.run_until_complete(run(cfg, stop_event=stop_event))
    finally:
        loop.close()


if __name__ == "__main__":
    main()
