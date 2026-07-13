#
# watch_bot.py — EVE's live voice loop for the WATCH (spec 2026-07-10 Piece 1).
#
# A NEW, standalone FastAPI process (the running phone_bot.py / bot.py are NEVER
# touched). The watch streams voice over ONE secure WebSocket — ordinary encrypted
# TCP that works out in the world (watch-behind-phone Bluetooth proxy, watch Wi-Fi,
# future LTE) — the way Google's watch assistant does. TLS + the token check live at
# the owner's public door (Tailscale Funnel by convention); this server re-checks the token
# runs the same pipecat pipeline the phone uses:
#
#   WS 16k PCM in -> Silero VAD -> SharedWhisperSTT -> the voice brain -> ChatterboxTTS
#                 -> WS 16k PCM out + JSON control events (listening/thinking/speaking)
#
# ---------------------------------------------------------------------------
# WIRE PROTOCOL  (WS /v1/watch/voice on 127.0.0.1:${EVE_WATCH_VOICE_PORT:-8791})
# ---------------------------------------------------------------------------
# Frames both directions:
#   * BINARY frame  = 16 kHz mono PCM16, little-endian, 20-40 ms chunks (raw, no header).
#   * TEXT frame    = one JSON object with a "type" field.
#
# Handshake (client -> server), FAIL-CLOSED:
#   The FIRST client frame MUST be text  {"type":"auth","token":"<app token>"}
#   (or the token may ride the query string:  /v1/watch/voice?token=<app token>).
#   The token is checked with hmac.compare_digest against EVE_APP_TOKEN /
#   approval_token.txt (same resolver as approval_api). A blank server token means the
#   process REFUSES TO START. A bad/missing/absent client token is answered with a named
#   fatal error frame and the socket is closed — never silence, never an open door.
#     server -> {"type":"error","error":"unauthorized|auth_required|bad_auth_frame|auth_timeout",
#                "fatal":true,"message":"..."}   then close(1008)
#
# After auth the server sends the ack, then the pipeline drives real state:
#   server -> {"type":"state","state":"connected"}          (auth accepted)
#            {"type":"state","state":"listening"}           (mic open, waiting / hearing)
#            {"type":"state","state":"thinking"}            (brain composing)
#            {"type":"state","state":"speaking"}            (TTS audio flowing)
#            {"type":"state","state":"idle"}                (session torn down)
#            {"type":"state","state":"preempted"}           (a new watch took the session)
#            {"type":"user_transcript","text":"..."}        (final Whisper transcription)
#            {"type":"bot_transcript","text":"..."}         (what EVE is saying)
#            {"type":"error","error":"...","fatal":true,"message":"..."}
#
# Client -> server control (after auth):
#   {"type":"interrupt"}   stop TTS mid-utterance (barge-in) and return to listening
#   {"type":"bye"}         end the session gracefully
#
# Half-duplex mic gate is ALWAYS on for the watch: the wrist speaker has no AEC, so EVE
# would hear her own voice and interrupt herself. The gate is NOT tied to the global
# barge_in_enabled setting (that setting is phone-only) — only two expert env overrides
# can change it (see _watch_half_duplex_on). One session at a time — a second connection
# PREEMPTS the first with a named "preempted" state, exactly like the phone loop's
# single-session takeover.
#
# systemd: deploy/eve-watch-voice.service.  Run standalone:  python watch_bot.py
#
import asyncio
import hmac
import json
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

# Load .env BEFORE local imports that read env at import time (persona, voice_llm, …),
# so the watch loop sees the same config as the phone loop. override=False so a test's
# monkeypatched env always wins.
load_dotenv()

import atlas_env

atlas_env.apply_aliases()  # ATLAS_* public names fan into EVE_*/JARVIS_*

from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket
from loguru import logger

# Lightweight pipecat imports only (frame classes + serializer base + a FrameProcessor).
# The HEAVY brain/STT/TTS wiring (jarvis_core, speech_factory, voice_llm, the websocket
# transport) is imported LAZILY inside _run_voice_session so this module imports clean for
# tests/lint without a GPU, a running brain, or the pipeline deps — mirroring phone_bot's
# discipline of never paying the pipeline cost just to import.
from pipecat.frames.frames import (
    ClientConnectedFrame,
    EndFrame,
    InputAudioRawFrame,
    InterruptionFrame,
    LLMFullResponseStartFrame,
    LLMRunFrame,
    OutputAudioRawFrame,
    OutputTransportMessageFrame,
    OutputTransportMessageUrgentFrame,
    TranscriptionFrame,
    TTSStartedFrame,
    TTSStoppedFrame,
    TTSTextFrame,
    UserStartedSpeakingFrame,
)
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor
from pipecat.serializers.base_serializer import FrameSerializer

logger.remove()
logger.add(sys.stderr, level=os.getenv("LOG_LEVEL", "INFO"))

# The watch streams 16 kHz mono PCM16 both ways. STT (Whisper) wants 16 kHz, so the input
# needs no resample; TTS emits 24 kHz, downsampled to 16 kHz NATIVELY by the output
# transport (audio_out_sample_rate=16000) before it hits the serializer — no hand-rolled
# numpy resampling.
WATCH_SAMPLE_RATE = 16000


# ---- Auth (fail-closed) — mirrors approval_api._resolve_app_token ------------
def _resolve_app_token() -> str:
    """Resolve the app token: env EVE_APP_TOKEN, else the contents of the token file
    (EVE_APP_TOKEN_FILE, default approval_token.txt). Blank -> refuse to start (never an
    open door). Called at server STARTUP (in the lifespan), not at import — so importing
    this module for tests/lint doesn't require the secret, while a running server still
    fails closed without it."""
    token = os.getenv("EVE_APP_TOKEN", "").strip()
    if not token:
        token_file = Path(os.getenv("EVE_APP_TOKEN_FILE", "approval_token.txt"))
        if token_file.is_file():
            token = token_file.read_text(encoding="utf-8").strip()
    if not token:
        raise RuntimeError(
            "EVE_APP_TOKEN is not set (and the token file is absent/empty). "
            "watch_bot refuses to start without an app token — fail-closed."
        )
    return token


# Resolved lazily at first use / server startup (NOT at import — see _resolve_app_token).
_APP_TOKEN: str | None = None


def _ensure_app_token() -> str | None:
    """Return the resolved token, resolving once on first use. Fail-CLOSED: with no token
    configured this returns None (every auth check then denies) rather than raising into a
    connection handler. The lifespan additionally resolves at boot so a misconfigured server
    dies at startup instead of silently denying every wrist."""
    global _APP_TOKEN
    if _APP_TOKEN is None:
        try:
            _APP_TOKEN = _resolve_app_token()
        except RuntimeError:
            return None
    return _APP_TOKEN


def _check(token: str | None) -> bool:
    expected = _ensure_app_token()
    if not token or not expected:
        return False
    return hmac.compare_digest(token, expected)


def _auth_timeout() -> float:
    return float(os.getenv("EVE_WATCH_AUTH_TIMEOUT_S", "10"))


def _watch_half_duplex_on() -> bool:
    """Whether the half-duplex mic gate is inserted for a wrist session.

    The watch NEVER consults the global barge_in_enabled setting (approval_store) — that
    setting is phone-only. On the wrist the speaker has no echo cancellation, so honoring a
    global barge-in flip let EVE's own voice into the mic and she interrupted herself
    constantly (hardware, 2026-07-11). The gate is therefore ON by default, with exactly two
    expert env escape hatches:
      * EVE_WATCH_ALLOW_INTERRUPTIONS=1 turns barge-in back on (skips the gate),
      * EVE_WATCH_HALF_DUPLEX=0 removes the gate entirely.
    The deliberate {"type":"interrupt"} tap still stops her regardless of this (it injects an
    InterruptionFrame and the pipeline runs allow_interruptions=True)."""
    barge_in = os.getenv("EVE_WATCH_ALLOW_INTERRUPTIONS", "0") == "1"
    return (not barge_in) and os.getenv("EVE_WATCH_HALF_DUPLEX", "1") == "1"


# ---- Wire codec: pipecat frames <-> watch frames ----------------------------
class WatchSerializer(FrameSerializer):
    """Boring, documented codec (see the WIRE PROTOCOL block up top).

    deserialize (client -> pipeline):
      binary            -> InputAudioRawFrame(16 kHz mono)
      {"type":"interrupt"} -> InterruptionFrame  (pipecat's barge-in/cancel signal: every
                              downstream processor runs _start_interruption, so TTS stops)
      {"type":"bye"}    -> EndFrame              (graceful pipeline stop)
      anything else     -> None (ignored; the auth frame is consumed pre-pipeline)

    serialize (pipeline -> client):
      OutputAudioRawFrame           -> raw 16 kHz PCM bytes
      OutputTransportMessage[Urgent]Frame -> the JSON control text (state / transcripts)
      everything else               -> None
    """

    def __init__(self, sample_rate: int = WATCH_SAMPLE_RATE):
        super().__init__()
        self._sample_rate = sample_rate

    async def serialize(self, frame):
        if isinstance(frame, OutputAudioRawFrame):
            return bytes(frame.audio)
        if isinstance(frame, (OutputTransportMessageFrame, OutputTransportMessageUrgentFrame)):
            try:
                return json.dumps(frame.message)
            except Exception as e:
                logger.warning(f"watch: undserializable control message dropped: {e!r}")
                return None
        return None

    async def deserialize(self, data):
        if isinstance(data, (bytes, bytearray)):
            return InputAudioRawFrame(
                audio=bytes(data), sample_rate=self._sample_rate, num_channels=1
            )
        try:
            msg = json.loads(data)
        except Exception:
            logger.warning("watch: undecodable control text frame dropped")
            return None
        if not isinstance(msg, dict):
            return None
        t = msg.get("type")
        if t == "interrupt":
            return InterruptionFrame()
        if t == "bye":
            return EndFrame()
        return None


# ---- State taps: real pipeline transitions -> control frames ----------------
# All outbound control text is routed as OutputTransportMessageFrame INTO the output
# transport, so every send (audio + control) funnels through the output transport's single
# writer — no concurrent writes to the raw WebSocket. Two taps because pipecat consumes the
# TranscriptionFrame at the user aggregator (it never reaches a processor placed after it),
# so user transcripts are tapped BEFORE the aggregator and the rest AFTER the TTS.
def _control(message: dict) -> OutputTransportMessageFrame:
    return OutputTransportMessageFrame(message=message)


class SttTap(FrameProcessor):
    """Placed between STT and the user aggregator: emit the final user transcript before the
    aggregator swallows the TranscriptionFrame."""

    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        if isinstance(frame, TranscriptionFrame):
            text = (getattr(frame, "text", "") or "").strip()
            if text:
                await self.push_frame(
                    _control({"type": "user_transcript", "text": text}),
                    FrameDirection.DOWNSTREAM,
                )
        await self.push_frame(frame, direction)


class StateEmitter(FrameProcessor):
    """Placed just before the output transport: turn the real turn transitions into
    {"type":"state"|"bot_transcript"} control frames so the watch orb morphs on GENUINE
    pipeline events — not synthetic timers.

    Thinking HEARTBEAT: a tool-using turn (browser, delegation) can run minutes with no
    pipeline frames at all, and the watch's think-timeout declared the call dead while EVE
    was mid-task (hardware-found 2026-07-10: "she pulled up something on the browser and
    then she stopped working"). While a turn is thinking, re-emit the thinking state every
    HEARTBEAT_S so the client KNOWS she's alive and keeps waiting."""

    HEARTBEAT_S = 10.0

    def __init__(self):
        super().__init__()
        self._hb_task = None

    def _stop_heartbeat(self):
        if self._hb_task is not None:
            self._hb_task.cancel()
            self._hb_task = None

    def _start_heartbeat(self):
        self._stop_heartbeat()

        async def _beat():
            while True:
                await asyncio.sleep(self.HEARTBEAT_S)
                await self.push_frame(
                    _control({"type": "state", "state": "thinking"}),
                    FrameDirection.DOWNSTREAM,
                )

        self._hb_task = asyncio.create_task(_beat())

    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        ev = None
        if isinstance(frame, ClientConnectedFrame):
            ev = {"type": "state", "state": "listening"}
        elif isinstance(frame, LLMFullResponseStartFrame):
            ev = {"type": "state", "state": "thinking"}
            self._start_heartbeat()
        elif isinstance(frame, TTSStartedFrame):
            ev = {"type": "state", "state": "speaking"}
            self._stop_heartbeat()
        elif isinstance(frame, TTSStoppedFrame):
            ev = {"type": "state", "state": "listening"}
            self._stop_heartbeat()
        elif isinstance(frame, UserStartedSpeakingFrame):
            ev = {"type": "state", "state": "listening"}
        elif isinstance(frame, TTSTextFrame):
            text = (getattr(frame, "text", "") or "").strip()
            if text:
                ev = {"type": "bot_transcript", "text": text}
        if isinstance(frame, EndFrame):
            self._stop_heartbeat()
        if ev is not None:
            await self.push_frame(_control(ev), FrameDirection.DOWNSTREAM)
        await self.push_frame(frame, direction)


# ---- Single session at a time (mirror phone_bot's takeover semantics) --------
# (task, websocket) of the live wrist session. A new connection preempts the old one with a
# named "preempted" state, then cancels its task and waits for teardown.
_current_session: tuple[asyncio.Task, WebSocket] | None = None


async def _safe_send_json(ws: WebSocket, message: dict) -> None:
    try:
        await ws.send_text(json.dumps(message))
    except Exception as e:
        logger.debug(f"watch: control send skipped ({e!r})")


async def _send_error(ws: WebSocket, error: str, message: str) -> None:
    """Named, FATAL error frame + close — the spec's honesty rule: failures are never
    silence."""
    await _safe_send_json(ws, {"type": "error", "error": error, "fatal": True, "message": message})
    try:
        await ws.close(code=1008)
    except Exception:
        pass


async def _authenticate(ws: WebSocket) -> bool:
    """Fail-closed handshake. True only when a valid token arrived (query param or the first
    text frame {type:auth}). Every rejection sends a NAMED error frame and closes."""
    qtoken = ws.query_params.get("token")
    if qtoken is not None:
        if _check(qtoken.strip()):
            return True
        await _send_error(ws, "unauthorized", "invalid app token")
        return False

    try:
        raw = await asyncio.wait_for(ws.receive(), timeout=_auth_timeout())
    except asyncio.TimeoutError:
        await _send_error(ws, "auth_timeout", "no auth frame received in time")
        return False
    except Exception:
        return False

    if raw.get("type") == "websocket.disconnect":
        return False
    if raw.get("bytes") is not None:
        await _send_error(ws, "auth_required", "binary audio before auth is rejected")
        return False

    text = raw.get("text")
    try:
        msg = json.loads(text)
    except Exception:
        await _send_error(ws, "bad_auth_frame", "first frame must be JSON {type:auth}")
        return False
    if not isinstance(msg, dict) or msg.get("type") != "auth":
        await _send_error(ws, "bad_auth_frame", "first frame must be {type:auth,token:...}")
        return False
    token = msg.get("token")
    if not isinstance(token, str) or not _check(token.strip()):
        await _send_error(ws, "unauthorized", "invalid or missing app token")
        return False
    return True


async def _preempt_previous() -> None:
    """Take over the single wrist session: tell the previous watch it was preempted, then
    close its socket so its pipeline tears down gracefully (a websocket close is the natural
    end for this transport — the input transport sees the disconnect and drains the pipeline).
    A cancel() backstop covers a session wedged off the socket; we wait, bounded, for teardown
    before building the new one so the old audio path can't race the new (phone_bot's fix)."""
    global _current_session
    prev = _current_session
    if not prev:
        return
    old_task, old_ws = prev
    if old_task.done():
        return
    await _safe_send_json(old_ws, {"type": "state", "state": "preempted"})
    try:
        await old_ws.close(code=1012)  # 1012 = service restart / taken over
    except Exception as e:
        logger.debug(f"watch: preempt close skipped ({e!r})")
    try:
        await asyncio.wait_for(asyncio.shield(old_task), timeout=10)
    except asyncio.TimeoutError:
        old_task.cancel()  # wedged off the socket — force it down
    except (asyncio.CancelledError, Exception) as e:
        logger.debug(f"watch: previous session ended with {e!r}")


# ---- The heavy pipeline (SEAM: tests monkeypatch _run_voice_session) ---------
async def _run_voice_session(ws: WebSocket) -> None:
    """One authenticated wrist session: the full EVE pipeline over this WebSocket. The
    heavy deps (jarvis_core / speech_factory / voice_llm / the websocket transport) are
    imported HERE, lazily, so importing watch_bot for tests/lint never pulls the brain."""
    from pipecat.audio.vad.silero import SileroVADAnalyzer
    from pipecat.pipeline.pipeline import Pipeline
    from pipecat.pipeline.worker import PipelineParams, PipelineWorker
    from pipecat.processors.aggregators.llm_response_universal import (
        LLMContextAggregatorPair,
        LLMUserAggregatorParams,
    )
    from pipecat.transports.websocket.fastapi import (
        FastAPIWebsocketParams,
        FastAPIWebsocketTransport,
    )

    from jarvis_core import build_context, register_tools
    from persona import USER_NAME as _OWNER
    from reminders_tool import ReminderService
    from sales_coach import try_load_business_context
    from speech_factory import MicGate, TrimmingAssistantAggregator, _build_stt, _build_tts
    from voice_llm import instr_role, make_voice_llm

    params = FastAPIWebsocketParams(
        audio_in_enabled=True,
        audio_out_enabled=True,
        audio_in_sample_rate=WATCH_SAMPLE_RATE,
        audio_out_sample_rate=WATCH_SAMPLE_RATE,  # native 24k(TTS)->16k downsample
        audio_in_channels=1,
        audio_out_channels=1,
        add_wav_header=False,
        serializer=WatchSerializer(WATCH_SAMPLE_RATE),
    )
    transport = FastAPIWebsocketTransport(websocket=ws, params=params)

    stt = _build_stt()
    llm = make_voice_llm()
    tts = _build_tts()

    context, protected_head = build_context()
    # Optional like the desktop loop: without a pack the coach tools refuse per-call
    # (loudly) — a missing sales pack must not crash the whole wrist voice session.
    business_pack = try_load_business_context()

    # The watch is the owner's PAIRED wrist device (like the phone). Mirror phone_bot's
    # legacy owner-override so tool calls aren't denied by the per-utterance speaker gate.
    import speaker_state

    speaker_state.reset()
    speaker_state.set_current(_OWNER, "owner", 1.0)
    speaker_state.grant_owner_override(float(os.getenv("EVE_WATCH_OWNER_TTL_S", "43200")))

    async def announce(instruction: str):
        context.add_message({"role": instr_role(), "content": instruction})
        speaker_state.set_current(_OWNER, "owner", 1.0)
        await worker.queue_frames([LLMRunFrame()])

    reminders = ReminderService(announce)
    register_tools(llm, context, business_pack, reminders, bridge=None)

    user_aggregator = LLMContextAggregatorPair(
        context,
        # audio_idle_timeout=0 disables the VAD idle watchdog: the watch's half-duplex
        # gate (below) stops mic frames whenever EVE speaks — BY DESIGN — and the
        # watchdog read that silence as a dead stream and forced speech stop 1s in,
        # cutting her off mid-sentence on the wrist.
        user_params=LLMUserAggregatorParams(
            vad_analyzer=SileroVADAnalyzer(), audio_idle_timeout=0
        ),
    ).user()
    assistant_aggregator = TrimmingAssistantAggregator(context, protected_head=protected_head)

    # Half-duplex mic gate — ALWAYS on for the watch (the global barge_in_enabled setting is
    # phone-only and is deliberately NOT read here: the wrist speaker has no AEC, so honoring
    # it made EVE interrupt herself off her own echo). The explicit {"type":"interrupt"} tap
    # still stops her regardless (it injects an InterruptionFrame; allow_interruptions=True
    # below makes that tap truly cancel TTS). Only the two env escape hatches in
    # _watch_half_duplex_on can turn the gate off, for an expert.
    mic_in = [transport.input()]
    if _watch_half_duplex_on():
        mic_in.append(MicGate(float(os.getenv("JARVIS_HALF_DUPLEX_TAIL_S", "0.6"))))
        logger.info("Half-duplex mic gate ON (watch) — EVE won't hear her own voice (barge_in_enabled ignored on the wrist)")
    else:
        logger.info("Half-duplex mic gate OFF (watch) — expert env override (EVE_WATCH_ALLOW_INTERRUPTIONS/EVE_WATCH_HALF_DUPLEX)")

    pipeline = Pipeline(
        [
            *mic_in,
            stt,
            SttTap(),
            user_aggregator,
            llm,
            tts,
            StateEmitter(),
            transport.output(),
            assistant_aggregator,
        ]
    )

    worker = PipelineWorker(
        pipeline,
        params=PipelineParams(
            enable_metrics=True,
            enable_usage_metrics=True,
            # Always allow interruptions at the PIPELINE level: the explicit tap-interrupt
            # frame must actually cancel TTS (with False it was a no-op — hardware-found
            # 2026-07-10). Voice barge-in stays governed by the MIC GATE above: while she
            # speaks the gate drops mic audio, so VAD can't self-interrupt off the wrist
            # speaker's echo; only the deliberate tap (or barge_in mode) cuts her off.
            allow_interruptions=True,
        ),
        idle_timeout_secs=None,
    )

    logger.info("watch session started")
    # Drive the worker DIRECTLY (worker.run blocks until the pipeline genuinely finishes and
    # propagates setup failures to us). WorkerRunner.add_workers+run() returned ~17ms into the
    # first real wrist call (hardware, 2026-07-10): added workers are auxiliary, the root set
    # was empty, auto_end tripped instantly — teardown fired mid-call and the live pipeline
    # kept running unsupervised (a working zombie). Never again: one worker, awaited here.
    from pipecat.workers.base_worker import WorkerParams

    try:
        await worker.run(WorkerParams(loop=asyncio.get_running_loop()))
    finally:
        reminders.cancel_all()
        await _safe_send_json(ws, {"type": "state", "state": "idle"})
        logger.info("watch session ended")


# ---- FastAPI app ------------------------------------------------------------
@asynccontextmanager
async def _lifespan(app: FastAPI):
    # Fail closed at startup: resolve the app token now (not at import) so a running server
    # never serves without one, while imports for tests/lint stay cheap.
    global _APP_TOKEN
    _APP_TOKEN = _resolve_app_token()  # raises RuntimeError if blank -> server won't start
    # Optional model warm-up (systemd sets EVE_WATCH_PRELOAD=1) so the first wrist turn isn't
    # paying the STT/TTS load cost. OFF by default so tests/CI never touch the GPU.
    if os.getenv("EVE_WATCH_PRELOAD", "0") == "1":
        def _warm():
            try:
                from speech_factory import _build_stt, _build_tts

                _build_stt()
                _build_tts()
                logger.info("watch: STT/TTS preloaded — first wrist turn is fast")
            except Exception as e:
                logger.warning(f"watch: model warm-up failed (first turn will retry): {e!r}")

        asyncio.get_running_loop().run_in_executor(None, _warm)
    yield


app = FastAPI(title="EVE watch voice", version="1.0", lifespan=_lifespan)


@app.websocket("/v1/watch/voice")
async def watch_voice(ws: WebSocket):
    global _current_session
    await ws.accept()
    if not await _authenticate(ws):
        return
    await _safe_send_json(ws, {"type": "state", "state": "connected"})

    # Single session: preempt any live wrist session, then claim it.
    await _preempt_previous()
    me = asyncio.current_task()
    _current_session = (me, ws)
    try:
        await _run_voice_session(ws)
    except asyncio.CancelledError:
        # Preempted by a newer connection: the preempted state was already sent.
        raise
    except Exception as e:
        logger.error(f"watch session crashed: {e!r}")
        await _send_error(ws, "session_error", f"voice pipeline error: {e}")
    finally:
        if _current_session and _current_session[0] is me:
            _current_session = None


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host=os.getenv("EVE_WATCH_VOICE_HOST", "127.0.0.1"),
        port=int(os.getenv("EVE_WATCH_VOICE_PORT", "8791")),
    )
