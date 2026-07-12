#
# Metrics + transcript bridge: a localhost WebSocket the openjarvis UI connects to.
#
# - MetricsBridge: runs the WebSocket server and fans out JSON messages to every
#   connected UI client.
# - MetricsWSObserver: a Pipecat observer that watches frames flowing through the
#   pipeline and forwards transcripts, speaking state, and per-turn metrics
#   (latency / token usage) so the X-Ray footer can render a live conversation.
#
# Messages are one-line JSON objects, each with a "type" field:
#   {"type":"status",            "mode":"local", "ws":"ready"}
#   {"type":"user_transcript",   "text":"what's on my plate today"}
#   {"type":"interim_transcript","text":"what's on my"}
#   {"type":"user_speaking",     "speaking":true|false}          (Silero VAD)
#   {"type":"thinking",          "active":true|false}            (LLM response window)
#   {"type":"token",             "n":3,"chars":17}               (LLM stream ticks, throttled)
#   {"type":"bot_transcript",    "text":"Here's your day."}      (what TTS is saying)
#   {"type":"bot_speaking",      "speaking":true|false}
#   {"type":"mic_level",         "value":0.34}                   (real mic RMS 0..1, ~15 Hz)
#   {"type":"bot_level",         "value":0.41}                   (real TTS audio RMS 0..1, ~15 Hz)
#   {"type":"tool_call",         "tool":"open_on_pc","args":"{...}","status":"running"}
#   {"type":"tool_result",       "tool":"open_on_pc","ok":true,"detail":"{...}"}
#   {"type":"metric",            "name":"TTFBMetricsData","processor":"...","value":0.18}
#   {"type":"usage",             "prompt_tokens":123,"completion_tokens":45}
#
# Every one of these is derived from a REAL frame flowing through the pipeline —
# nothing here is synthesized for looks. When the pipeline is silent, the bridge
# is silent, and the UI's neural graph goes calm. That silence is the proof.
#

import asyncio
import json
import math
import os
import time
from array import array
from collections import deque
from datetime import datetime
from pathlib import Path

import websockets
from loguru import logger

from pipecat.observers.base_observer import BaseObserver, FramePushed

# Conversation log: every meaningful event (not the 15 Hz audio levels) is
# appended as one JSON line to transcripts/YYYY-MM-DD.jsonl so conversations
# can be reviewed after the fact — what was said, what Jarvis answered, which
# tools ran and whether they succeeded, and per-turn latency.
_LOGGED_TYPES = {
    "user_transcript",
    "interim_transcript",
    "bot_transcript",
    "user_speaking",
    "bot_speaking",
    "thinking",
    "tool_call",
    "tool_result",
    "sms_received",
    "usage",
    "metric",
    "status",
    # Delegation trace events (emitted by agent_bridge.handle_jarvis_agent): the
    # per-brain waterfall made visible. Logged in full (the 220-char _short clip
    # lives only in MetricsWSObserver, which these never pass through) so the
    # archive + hub can show the complete step tree and untruncated result.
    "delegation_start",
    "delegation_step",
    "delegation_end",
    # Agent talk-back lifecycle (a2a_fabric.handle_push -> agent_delivery.deliver_update
    # -> bridge.broadcast): the transcript is the ONE artifact that crosses the process
    # boundary to approval_api's live forwarder, so dropping these here blinds the phone
    # app's Approvals live feed.
    "agent_progress",
    "agent_question",
    "agent_result",
    "agent_blocker",
    "agent_task_assigned",
    "agent_task_cancelled",
    "agent_task_redirected",
}
_NOISY_TYPES = {"interim_transcript", "user_speaking", "bot_speaking", "metric", "status"}


class TranscriptLogger:
    """Append-only JSONL conversation log, one file per day."""

    def __init__(self, log_dir: str | None = None, tag: str | None = None):
        self._dir = Path(log_dir or os.getenv("JARVIS_LOG_DIR", Path(__file__).parent / "transcripts"))
        self._dir.mkdir(parents=True, exist_ok=True)
        self._verbose = os.getenv("JARVIS_LOG_VERBOSE", "0") == "1"
        # Which body wrote the line ("local"/"phone") — both processes append
        # to the same per-day file so review_conversations sees everything.
        self._tag = tag
        logger.info(f"Transcript log -> {self._dir / 'YYYY-MM-DD.jsonl'}")

    def log(self, message: dict):
        mtype = message.get("type")
        if mtype not in _LOGGED_TYPES:
            return
        if mtype in _NOISY_TYPES and not self._verbose:
            # Keep the default log review-friendly: speech, tools, usage only.
            if mtype != "metric" or message.get("name") != "TTFBMetricsData":
                return
        try:
            now = datetime.now()
            stamped = {"ts": now.isoformat(timespec="milliseconds"), **message}
            if self._tag:
                stamped["src"] = self._tag
            line = json.dumps(stamped, default=str)
            with open(self._dir / f"{now:%Y-%m-%d}.jsonl", "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception as e:  # logging must never break the voice loop
            logger.debug(f"TranscriptLogger skipped an event: {e}")


def _thinking_state() -> bool:
    """Current persistent thinking-mode toggle (Epic T); False on any error (never block the WS)."""
    try:
        import thinking_state
        return thinking_state.enabled()
    except Exception:
        return False


class MetricsBridge:
    def __init__(self, host: str, port: int, mode: str):
        self._host = host
        self._port = port
        self._mode = mode
        self._clients: set = set()
        self._server = None
        self._transcript_log = TranscriptLogger(tag=mode)
        # Live pipeline state, maintained by MetricsWSObserver — announce()
        # gates on this so a spoken interruption can't land mid-turn.
        self.thinking = False
        self.bot_speaking = False

    @property
    def busy(self) -> bool:
        return self.thinking or self.bot_speaking

    async def start(self):
        self._server = await websockets.serve(self._handler, self._host, self._port)
        logger.info(f"Metrics bridge listening on ws://{self._host}:{self._port}")

    async def _handler(self, websocket):
        self._clients.add(websocket)
        # Greet the new UI client with current status + the persistent thinking-mode state, so a
        # freshly-opened sidecar app shows the toggle in the right position immediately.
        await self._safe_send(websocket, {"type": "status", "mode": self._mode, "ws": "ready"})
        await self._safe_send(websocket, {"type": "thinking_mode", "enabled": _thinking_state()})
        try:
            async for raw in websocket:
                await self._on_control(raw)
        finally:
            self._clients.discard(websocket)

    async def _on_control(self, raw) -> None:
        """Inbound control channel (UI -> voice loop). Currently the manual thinking toggle
        (Epic T): {type:'set_thinking', on:bool} flips thinking_state and broadcasts the new
        thinking_mode to every client. Distinct from the ephemeral {type:'thinking', active}
        'EVE is reasoning right now' signal. Malformed/unknown input is ignored — never crash."""
        try:
            msg = json.loads(raw)
        except Exception:
            return
        if not isinstance(msg, dict) or msg.get("type") != "set_thinking":
            return
        on = bool(msg.get("on"))
        try:
            import thinking_state
            thinking_state.set_enabled(on)
        except Exception as e:
            logger.warning(f"set_thinking control failed: {e}")
            return
        await self.broadcast({"type": "thinking_mode", "enabled": on})

    async def broadcast(self, message: dict):
        # Log first: the conversation record exists even with no UI connected.
        self._transcript_log.log(message)
        if not self._clients:
            return
        data = json.dumps(message)
        await asyncio.gather(
            *[self._raw_send(c, data) for c in list(self._clients)],
            return_exceptions=True,
        )

    async def _raw_send(self, ws, data: str):
        try:
            await ws.send(data)
        except Exception:
            self._clients.discard(ws)

    async def _safe_send(self, ws, message: dict):
        await self._raw_send(ws, json.dumps(message))


# Audio level events above ~15 Hz add nothing visually and waste the loop.
_LEVEL_INTERVAL = 1.0 / 15
# Token ticks are batched on the same clock so a fast stream can't flood the WS.
_TOKEN_INTERVAL = 1.0 / 15


def _short(value, limit: int = 220) -> str:
    """Compact, truncated string form of tool args/results for the UI."""
    if value is None:
        return ""
    try:
        s = json.dumps(value, default=str)
    except Exception:
        s = str(value)
    return s[:limit] + ("…" if len(s) > limit else "")


def _result_ok(result) -> bool:
    """Best-effort success flag from a tool result payload. Tools in this repo
    return dicts with 'ok' or 'opened'; anything with an 'error' key is a failure."""
    if isinstance(result, dict):
        for key in ("ok", "opened", "started", "ended", "done"):
            if key in result:
                return bool(result[key])
        if "error" in result:
            return False
    return result is not None


def _rms_level(frame) -> float | None:
    """True 0..1 RMS of a raw 16-bit PCM audio frame, or None if unreadable."""
    audio = getattr(frame, "audio", None)
    if not audio or len(audio) < 2:
        return None
    try:
        samples = array("h")  # 16-bit signed PCM
        samples.frombytes(audio[: len(audio) - (len(audio) % 2)])
        # Stride so even big chunks cost ~256 multiplies.
        step = max(1, len(samples) // 256)
        acc = 0
        n = 0
        for i in range(0, len(samples), step):
            v = samples[i]
            acc += v * v
            n += 1
        rms = math.sqrt(acc / n) if n else 0.0
    except Exception:
        return None
    # 3000 ≈ loud speech on a 16-bit scale; clamp into a lively 0..1.
    return min(1.0, rms / 3000.0)


class MetricsWSObserver(BaseObserver):
    """Forward useful frames to the UI without disturbing the pipeline."""

    def __init__(self, bridge: MetricsBridge):
        super().__init__()
        self._bridge = bridge
        self._last_mic_level = 0.0
        self._last_bot_level = 0.0
        self._next_mic_send = 0.0
        self._next_bot_send = 0.0
        self._pending_tokens = 0
        self._pending_chars = 0
        self._next_token_send = 0.0
        # The observer sees a frame once per processor hop (tts -> output ->
        # aggregator = 2-3 sightings of the SAME frame), which doubled every
        # transcript line on the wire and in the JSONL log. Dedupe by frame id.
        # Pipecat's broadcast_frame() additionally emits an upstream/downstream
        # SIBLING PAIR with different ids (linked via broadcast_sibling_id) —
        # tool_call/tool_result frames arrive as such pairs, so treat a frame
        # whose sibling was already seen as a duplicate too.
        self._seen_ids: set = set()
        self._seen_order: deque = deque()

    async def on_push_frame(self, data: FramePushed):
        frame = data.frame
        fid = getattr(frame, "id", None)
        if fid is not None:
            sibling = getattr(frame, "broadcast_sibling_id", None)
            if fid in self._seen_ids or (sibling is not None and sibling in self._seen_ids):
                return
            self._seen_ids.add(fid)
            self._seen_order.append(fid)
            if len(self._seen_order) > 4096:
                self._seen_ids.discard(self._seen_order.popleft())
        name = type(frame).__name__

        try:
            if name == "TranscriptionFrame":
                text = getattr(frame, "text", "") or ""
                if text.strip():
                    await self._bridge.broadcast({"type": "user_transcript", "text": text})

            elif name == "InterimTranscriptionFrame":
                text = getattr(frame, "text", "") or ""
                if text.strip():
                    await self._bridge.broadcast({"type": "interim_transcript", "text": text})

            elif name == "UserStartedSpeakingFrame":
                await self._bridge.broadcast({"type": "user_speaking", "speaking": True})

            elif name == "UserStoppedSpeakingFrame":
                await self._bridge.broadcast({"type": "user_speaking", "speaking": False})

            elif name == "LLMFullResponseStartFrame":
                self._bridge.thinking = True
                await self._bridge.broadcast({"type": "thinking", "active": True})

            elif name == "LLMFullResponseEndFrame":
                self._bridge.thinking = False
                await self._flush_tokens(force=True)
                await self._bridge.broadcast({"type": "thinking", "active": False})

            elif name == "LLMTextFrame":
                # One tick per streamed chunk, batched to ~15 Hz.
                self._pending_tokens += 1
                self._pending_chars += len(getattr(frame, "text", "") or "")
                await self._flush_tokens()

            elif name == "TTSTextFrame":
                text = getattr(frame, "text", "") or ""
                if text.strip():
                    await self._bridge.broadcast({"type": "bot_transcript", "text": text})

            elif name == "BotStartedSpeakingFrame":
                self._bridge.bot_speaking = True
                await self._bridge.broadcast({"type": "bot_speaking", "speaking": True})

            elif name == "BotStoppedSpeakingFrame":
                self._bridge.bot_speaking = False
                self._last_bot_level = 0.0
                await self._bridge.broadcast({"type": "bot_speaking", "speaking": False})
                await self._bridge.broadcast({"type": "bot_level", "value": 0.0})

            elif name == "InputAudioRawFrame":
                level = _rms_level(frame)
                if level is not None:
                    now = time.monotonic()
                    self._last_mic_level = level
                    if now >= self._next_mic_send:
                        self._next_mic_send = now + _LEVEL_INTERVAL
                        await self._bridge.broadcast({"type": "mic_level", "value": round(level, 3)})

            elif name in ("TTSAudioRawFrame", "OutputAudioRawFrame", "SpeechOutputAudioRawFrame"):
                level = _rms_level(frame)
                if level is not None:
                    now = time.monotonic()
                    self._last_bot_level = level
                    if now >= self._next_bot_send:
                        self._next_bot_send = now + _LEVEL_INTERVAL
                        await self._bridge.broadcast({"type": "bot_level", "value": round(level, 3)})

            elif name == "FunctionCallInProgressFrame":
                await self._bridge.broadcast(
                    {
                        "type": "tool_call",
                        "tool": getattr(frame, "function_name", "?"),
                        "args": _short(getattr(frame, "arguments", None)),
                        "status": "running",
                    }
                )

            elif name == "FunctionCallResultFrame":
                result = getattr(frame, "result", None)
                await self._bridge.broadcast(
                    {
                        "type": "tool_result",
                        "tool": getattr(frame, "function_name", "?"),
                        "ok": _result_ok(result),
                        "detail": _short(result),
                    }
                )

            elif name == "FunctionCallCancelFrame":
                await self._bridge.broadcast(
                    {
                        "type": "tool_result",
                        "tool": getattr(frame, "function_name", "?"),
                        "ok": False,
                        "detail": "cancelled (interrupted)",
                    }
                )

            elif name == "MetricsFrame":
                for m in getattr(frame, "data", []) or []:
                    payload = {
                        "type": "metric",
                        "name": type(m).__name__,
                        "processor": getattr(m, "processor", None),
                    }
                    # Latency-style metrics expose .value; usage metrics expose .value.tokens.
                    value = getattr(m, "value", None)
                    if isinstance(value, (int, float)):
                        payload["value"] = value
                        await self._bridge.broadcast(payload)
                    else:
                        tokens = getattr(value, "__dict__", None)
                        if tokens:
                            await self._bridge.broadcast(
                                {"type": "usage", "processor": payload["processor"], **tokens}
                            )
        except Exception as e:  # never let observability crash the voice loop
            logger.debug(f"MetricsWSObserver skipped a frame: {e}")

    async def _flush_tokens(self, force: bool = False):
        if self._pending_tokens == 0:
            return
        now = time.monotonic()
        if not force and now < self._next_token_send:
            return
        self._next_token_send = now + _TOKEN_INTERVAL
        n, chars = self._pending_tokens, self._pending_chars
        self._pending_tokens = 0
        self._pending_chars = 0
        await self._bridge.broadcast({"type": "token", "n": n, "chars": chars})
