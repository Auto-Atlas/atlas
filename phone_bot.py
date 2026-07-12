#
# Jarvis phone — the same brain, a second set of ears. A WebRTC voice loop
# served to the owner's phone over the tailnet:
#
#   phone browser (PWA) --HTTPS/WebRTC--> tailscale serve --> this server
#        mic audio  ------------------------>  Whisper (GPU, this PC)
#        TTS audio  <------------------------  Kokoro  (this PC)
#                          qwen3:8b + the FULL tool registry in between
#
# Same persona, same tools, same wiki memory as the desktop loop (bot.py) —
# the whole assembly comes from jarvis_core, so the two bodies can't drift.
# The two loops are separate conversations (contexts) for now; they share
# long-term memory through the wiki.
#
# Run on WINDOWS-NATIVE Python next to bot.py. Listens on 127.0.0.1:8788;
# expose with:  tailscale serve --bg --https=8444 http://localhost:8788
# then open https://<your-pc>.<tailnet>.ts.net:8444 on the phone.
#

import os
import sys
import time
from pathlib import Path

import mic_control

# Same GPU DLL registration as bot.py — faster-whisper needs the nvidia pip
# wheels' bin dirs on PATH no matter how this process is launched.
if sys.platform == "win32":
    _nvidia_root = Path(__file__).parent / ".venv" / "Lib" / "site-packages" / "nvidia"
    for _bin in sorted(_nvidia_root.glob("*/bin")):
        os.add_dll_directory(str(_bin))
        os.environ["PATH"] = f"{_bin};{os.environ.get('PATH', '')}"

# Single-instance guard on its own port (the desktop loop holds 8764). Must NOT
# be 8790 -- the A2A Hermes adapter serves there (EVE_A2A_PORT default), and a
# running adapter would make this guard exit 111 on a port it never owned.
import socket

_INSTANCE_LOCK = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    _INSTANCE_LOCK.bind(("127.0.0.1", int(os.getenv("JARVIS_PHONE_LOCK_PORT", "8763"))))
    _INSTANCE_LOCK.listen(1)
except OSError:
    print("jarvis-phone: another instance already holds the lock port — exiting.", file=sys.stderr)
    sys.exit(111)

from dotenv import load_dotenv

load_dotenv(override=True)

import atlas_env

atlas_env.apply_aliases()  # ATLAS_* public names fan into EVE_*/JARVIS_*

import asyncio
import contextlib

from loguru import logger

from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.frames.frames import (
    InterruptionFrame,
    LLMRunFrame,
    TTSSpeakFrame,
)
from pipecat.processors.frame_processor import FrameProcessor


class InterruptionSuppressor(FrameProcessor):
    """Kills barge-in: swallows InterruptionFrame so NOTHING (incl. EVE's own speaker
    echo) can cut her off mid-sentence. The TTS + output transport only stop when they
    SEE an InterruptionFrame (tts_service / base_output) — drop it here, just before
    them, and she always finishes. Turn-taking still works (she finishes, then you
    talk); the app's End button is the manual stop. Stands down when the "Let me
    interrupt EVE" toggle is ON (barge_in) or via JARVIS_SUPPRESS_INTERRUPTIONS=0."""

    def __init__(self, barge_in: bool = False):
        super().__init__()
        self._active = (not barge_in) and os.getenv("JARVIS_SUPPRESS_INTERRUPTIONS", "1") == "1"

    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        if self._active and isinstance(frame, InterruptionFrame):
            return  # no barge-in — she can't be interrupted by sound
        await self.push_frame(frame, direction)
from pipecat.pipeline.pipeline import Pipeline
from pipecat.pipeline.worker import PipelineParams, PipelineWorker
from pipecat.processors.aggregators.llm_response_universal import (
    LLMAssistantAggregator,
    LLMContextAggregatorPair,
    LLMUserAggregatorParams,
)
from pipecat.transports.base_transport import TransportParams
from pipecat.transports.smallwebrtc.transport import SmallWebRTCTransport
from pipecat.workers.runner import WorkerRunner

from bridge import MetricsBridge, MetricsWSObserver
from jarvis_core import build_context, register_tools, trim_messages
from reminders_tool import ReminderService
from sales_coach import try_load_business_context
from persona import USER_NAME, USER_NICK
from voice_llm import active_profile, instr_role_for, make_voice_llm

logger.remove(0)
logger.add(sys.stderr, level=os.getenv("LOG_LEVEL", "INFO"))


# ---- Speech factory --------------------------------------------------------
# The STT/TTS builders + shared speech processors moved to speech_factory.py
# (side-effect-free) so jetson_bot can reuse them WITHOUT importing this module
# (which binds the single-instance lock socket + sys.exit(111)s above).
from speech_factory import (  # noqa: E402
    BotEchoRecorder,
    EchoGuard,
    MicGate,
    TrimmingAssistantAggregator,
    _build_stt,
    _build_tts,
)


def _daily_briefing_due() -> bool:
    """True on the FIRST phone connect of a new calendar day (then marks today done),
    or always when JARVIS_BRIEFING_FORCE=1 (for testing). Best-effort: any error returns
    False so briefing state can never block a connect."""
    if os.getenv("JARVIS_BRIEFING_FORCE", "0") == "1":
        return True
    try:
        today = time.strftime("%Y-%m-%d")
        path = os.path.join(os.path.dirname(__file__), ".last_briefing")
        last = ""
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as f:
                last = f.read().strip()
        if last == today:
            return False
        # Atomic write: a crash mid-write must never leave a truncated/empty marker that
        # would re-fire (or wrongly suppress) the briefing. Write to a temp file in the same
        # dir, then os.replace (atomic rename on the same filesystem).
        tmp = f"{path}.{os.getpid()}.tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(today)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
        return True
    except Exception as e:
        logger.debug(f"briefing-state check failed: {e}")
        return False


# The proactive morning rundown EVE gives on the first connect of the day. She CALLS the
# real tools (owner session) and summarizes — never guesses — then offers to act (draft→approve).
_BRIEFING_INSTRUCTION = (
    "{user} just connected — his first time today. Give a brief morning rundown, like a sharp "
    "chief of staff. FIRST actually CALL these tools to get REAL data (never guess, never read "
    "from memory): check_email, get_calendar, check_inbox, get_weather. THEN say a SHORT spoken "
    "summary — two or three sentences total — of ONLY what matters: anything urgent in email, "
    "what's on today's calendar, anything new in the inbox, and the weather in a quick phrase. "
    "Do NOT read everything or list raw data. END by asking if he'd like you to handle any of it. "
    "If he says yes to something, draft it and bring it back for his approval before doing anything."
)


def preload_models():
    """Pay the model-load cost at process boot instead of on first connect."""
    t0 = time.time()
    logger.info("preloading Whisper + Kokoro models...")
    _build_stt()
    _build_tts()
    logger.info(f"models preloaded in {time.time() - t0:.1f}s — first connect is now fast")


# ---- Transcript/metrics bridge ----------------------------------------------
# Same observer as the desktop loop, own port: phone conversations land in the
# shared transcripts/ JSONL (tagged src="phone") so review_conversations sees
# them, and a UI can subscribe to ws://127.0.0.1:8766 if ever wanted.
_bridge: MetricsBridge | None = None


async def _get_bridge() -> MetricsBridge:
    global _bridge
    if _bridge is None:
        b = MetricsBridge(
            host=os.getenv("JARVIS_PHONE_WS_HOST", "127.0.0.1"),
            port=int(os.getenv("JARVIS_PHONE_WS_PORT", "8766")),
            mode="phone",
        )
        await b.start()
        _bridge = b
    return _bridge


class TrimmingAssistantAggregator(LLMAssistantAggregator):
    """Assistant aggregator that trims the shared context after every committed
    turn. The desktop loop (bot.py) trims in its announce() path; the phone loop
    had NO trim at all — a long call grew the history unbounded until the SYSTEM
    PROMPT (persona + tool rules + honesty/safety contract) fell out of the top
    of the model's window and EVE lost her contract mid-conversation (Ollama
    truncates from the top silently). Trimming here, at the natural per-turn
    boundary, always preserves the protected head."""

    def __init__(self, *args, protected_head: int, **kwargs):
        super().__init__(*args, **kwargs)
        self._protected_head = protected_head

    async def _handle_push_aggregation(self):
        await super()._handle_push_aggregation()
        msgs = self.context.get_messages()
        trimmed = trim_messages(msgs, self._protected_head)
        if len(trimmed) != len(msgs):
            self.context.set_messages(trimmed)
            logger.info(f"Phone context trimmed: {len(msgs)} -> {len(trimmed)} messages")


# One phone session at a time — a new connection replaces the previous one.
_current_session: asyncio.Task | None = None


def build_transport(runner_args):
    """Pluggable voice transport. Default = self-hosted SmallWebRTC (current
    behavior, byte-for-byte unchanged). ``JARVIS_VOICE_TRANSPORT=livekit`` uses
    the open-source managed-WebRTC path (Phase A) so audio rides a media server
    that's reachable from any network — no LAN/firewall/Tailscale dependence.
    See docs/EVE-VOICE-LIVEKIT-INTEGRATION.md. The STT/LLM/TTS pipeline is
    identical across transports; only ingress/egress changes."""
    mode = os.getenv("JARVIS_VOICE_TRANSPORT", "smallwebrtc").lower()
    params = TransportParams(audio_in_enabled=True, audio_out_enabled=True)
    if mode == "smallwebrtc":
        return SmallWebRTCTransport(
            webrtc_connection=runner_args.webrtc_connection, params=params
        )
    if mode == "livekit":
        # Guarded import: only pulled when livekit is selected, so the default
        # path never needs pipecat-ai[livekit] installed.
        from pipecat.transports.livekit.transport import (
            LiveKitParams,
            LiveKitTransport,
        )

        url = getattr(runner_args, "ws_url", None) or os.getenv(
            "LIVEKIT_URL", "ws://localhost:7880"
        )
        token = getattr(runner_args, "bot_token", None)
        room = getattr(runner_args, "room", None) or os.getenv("LIVEKIT_ROOM", "eve-call")
        if not token:
            raise RuntimeError(
                "JARVIS_VOICE_TRANSPORT=livekit needs a bot token on runner_args "
                "(mint via livekit_rooms.new_call()); see EVE-VOICE-LIVEKIT-INTEGRATION.md"
            )
        return LiveKitTransport(
            url=url,
            token=token,
            room_name=room,
            params=LiveKitParams(audio_in_enabled=True, audio_out_enabled=True),
        )
    raise RuntimeError(f"unknown JARVIS_VOICE_TRANSPORT={mode!r}")


async def bot(runner_args):
    """One phone voice session: full Jarvis pipeline over this WebRTC peer.
    Invoked by pipecat's development runner for each accepted /start +
    SDP offer (SmallWebRTCRunnerArguments carries the live connection)."""
    global _current_session
    old = _current_session
    if old and not old.done():
        old.cancel()
        # Wait for the old session to actually tear down before building the
        # new one — starting immediately raced its audio/WebRTC cleanup.
        try:
            await asyncio.wait_for(old, timeout=10)
        except (asyncio.CancelledError, asyncio.TimeoutError):
            pass
        except Exception as e:
            logger.debug(f"previous session ended with: {e!r}")
    _current_session = asyncio.current_task()
    session_task = _current_session

    connection = getattr(runner_args, "webrtc_connection", None)
    transport = build_transport(runner_args)

    stt = _build_stt()
    # Resolve the brain profile ONCE for this session so the LLM and every injected
    # instruction's role agree; a mid-session voice_brain switch applies on reconnect.
    _session_profile = active_profile()
    llm = make_voice_llm(_session_profile)
    _instr_role = instr_role_for(_session_profile)
    tts = _build_tts()

    context, protected_head = build_context()
    # None = no pack written yet; the coach tools refuse until one exists.
    business_pack = try_load_business_context()

    # The phone is the owner's own device. (Reset first so a stale tier from a
    # previous session can't leak into the greeting turn.)
    import speaker_state
    from persona import USER_NAME as _OWNER
    speaker_state.reset()
    if os.getenv("EVE_IDENTITY_V2") == "1":
        # Spec-5 identity model: the phone is the owner's PAIRED device, so it
        # carries DEVICE trust (general chat + low/medium-risk tools) — but
        # owner-gated capabilities (high-risk, OWNER_ONLY, owner-private memory)
        # wait for a real speaker match or the short re-auth phrase. No fake owner
        # score, no 12h blanket. Device trust is what stops the "keep real tasks to
        # the family" deflection when speaker-ID is absent/lapsed.
        import identity
        speaker_state.set_device(identity.DevicePrincipal.owner("phone"))
    else:
        # Legacy behavior (UNCHANGED — default). The phone is a single-owner,
        # paired, tailnet-only device with no stranger-at-the-desk risk. The
        # desktop's per-utterance speaker-ID gate FAILS CLOSED to "unknown" here
        # (resemblyzer absent, or the owner never enrolled) and the 30s tier TTL
        # also lapses mid-conversation — either one denies every tool with the
        # "keep real tasks to the family" deflection. grant_owner_override() is
        # checked first in current_tier(), so a session-long window forces owner
        # regardless of voice match or TTL. Tunable via env.
        speaker_state.set_current(_OWNER, "owner", 1.0)
        speaker_state.grant_owner_override(
            float(os.getenv("JARVIS_PHONE_OWNER_TTL_S", "43200"))
        )

    async def announce(instruction: str):
        # Raise when this session is no longer the live one: ReminderService
        # treats a failed announce as "not delivered" and leaves the record
        # on disk, instead of speaking into a dead pipeline and deleting it.
        if _current_session is not session_task or session_task.done():
            raise RuntimeError("phone session is no longer live")
        context.add_message({"role": _instr_role, "content": instruction})
        if os.getenv("EVE_IDENTITY_V2") == "1":
            # Proactive system turn on the owner's paired device: a short, bounded
            # owner unlock (the re-auth window), never the 12h blanket. Lets a
            # proactive briefing use owner-gated tools without faking a voice match.
            speaker_state.grant_owner_override(120)
        else:
            speaker_state.set_current(_OWNER, "owner", 1.0)   # owner's device, system turn
        await worker.queue_frames([LLMRunFrame()])

    # Reminders SET from the phone persist to the shared reminders.json and
    # fire here while this session is up; the desktop loop announces anything
    # missed at its next boot. No start() — the desktop loop owns reloads.
    reminders = ReminderService(announce)

    # Build the bridge first so jarvis_agent's delegation trace events can stream
    # to the phone session. _get_bridge() is memoized, so the later call returns
    # this same instance.
    bridge = await _get_bridge()
    register_tools(llm, context, business_pack, reminders, bridge=bridge)

    # User aggregator from the pair (keeps its VAD params); the assistant side is
    # the trimming variant on the SAME shared context, so it sees and trims every
    # committed turn. Default assistant params match the pair's (phone never set
    # custom assistant params).
    user_aggregator = LLMContextAggregatorPair(
        context,
        user_params=LLMUserAggregatorParams(vad_analyzer=SileroVADAnalyzer()),
    ).user()
    assistant_aggregator = TrimmingAssistantAggregator(
        context, protected_head=protected_head
    )

    # "Let me interrupt EVE" toggle (app Status switch -> settings table, read here at
    # session start). ON => real barge-in: allow interruptions AND drop the half-duplex
    # gate so your voice reaches her mid-sentence (best on a headset / with AEC). Default
    # OFF = speakerphone-safe (her own TTS echo can't barge in on her).
    try:
        import approval_store
        _bi = approval_store.get_setting("barge_in_enabled")
        barge_in = (str(_bi).strip().lower() == "true") if _bi is not None \
            else os.getenv("JARVIS_PHONE_ALLOW_INTERRUPTIONS", "0") == "1"
    except Exception as e:
        logger.warning(f"barge_in setting read failed ({e}); env fallback")
        barge_in = os.getenv("JARVIS_PHONE_ALLOW_INTERRUPTIONS", "0") == "1"

    # Half-duplex by default on the phone (speakerphone, no AEC) so EVE never hears
    # herself — SKIPPED when barge-in is ON. Insert the gate right after mic input, before STT.
    mic_in = [transport.input()]
    if not barge_in and os.getenv("JARVIS_PHONE_HALF_DUPLEX", "1") == "1":
        mic_in.append(MicGate(float(os.getenv("JARVIS_HALF_DUPLEX_TAIL_S", "0.6"))))
        logger.info("Half-duplex mic gate ON (phone) — EVE won't hear her own voice; barge-in disabled")
    elif barge_in:
        logger.info("Barge-in ON (phone) — interruptions enabled, half-duplex gate skipped")

    # Text-level echo backstop: playback lags BotStoppedSpeaking, so the MicGate
    # tail can miss the end of a long sentence - the guard drops transcriptions
    # that are echoes of what EVE just said (see speech_factory.EchoGuard).
    echo_guard = EchoGuard()

    pipeline = Pipeline(
        [
            *mic_in,
            stt,
            echo_guard,
            user_aggregator,
            llm,
            InterruptionSuppressor(barge_in),  # echo can't cut her off unless the toggle says so
            tts,
            BotEchoRecorder(echo_guard),
            transport.output(),
            assistant_aggregator,
        ]
    )

    worker = PipelineWorker(
        pipeline,
        params=PipelineParams(
            enable_metrics=True,
            enable_usage_metrics=True,
            # On speakerphone EVE's own TTS echoes into the mic and the STT
            # transcribes it — with interruptions ON that echo BARGES IN and
            # cancels her in-progress turn/tool (observed: run_morning_brief
            # cancelled by its own "on it" ack). Default OFF so her own voice
            # can't cut her off; set JARVIS_PHONE_ALLOW_INTERRUPTIONS=1 to allow
            # real barge-in (best with earbuds / real AEC).
            allow_interruptions=barge_in,
        ),
        observers=[MetricsWSObserver(bridge)],
        idle_timeout_secs=None,
    )

    # Short hello so you know the link is alive the moment audio flows.
    # Proactive connect greeting is OFF by default (the owner: no greeting on every reconnect —
    # "she'll speak when we speak"). Set JARVIS_BOOT_GREETING=1 to restore the phone hello.
    if os.getenv("JARVIS_DAILY_BRIEFING", "1") == "1" and _daily_briefing_due():
        # Code-orchestrated: fetch the real data ourselves (concurrent, isolated,
        # timeout-bounded) and hand EVE one fact block to narrate in a SINGLE fast
        # turn — no model tool-chaining, so it can't blow the app's connect timeout.
        import briefing

        _full_brief = ""   # the whole ritual as ONE hard-muted monologue (morning only)
        _dash: dict = {}   # life dashboard (set in the morning branch, for the strategist)
        try:
            data = await briefing.gather_briefing()
            # Morning wake-up (incl. the native 5 AM alarm connect) gets the full ritual —
            # whys + goals + briefing + a fitness/eating charge. Later in the day, the first
            # connect still gets the quick briefing (the ritual recital would feel heavy at 2pm).
            from datetime import datetime as _dt

            cutoff = int(os.getenv("JARVIS_RITUAL_MORNING_CUTOFF_HOUR", "11"))
            if _dt.now().hour < cutoff:
                import rituals

                _dash = rituals.load_dashboard()
                # 5 AM wake content (EVE_MORNING_MODE): "whys" (default) = JUST the reasons
                # you get up — gentle, the full brief is too heavy at 5 AM; "full" = whys +
                # goals + briefing. Either way it's ONE hard-muted block (no model narration)
                # so on speakerphone her own voice can't echo into a self-reply loop.
                if os.getenv("EVE_MORNING_MODE", "whys").lower() == "whys":
                    _whys = rituals.morning_whys_speech(_dash, USER_NICK)
                    _full_brief = (
                        _whys + " It's a new day. Rise up — it's yours."
                    ) if _whys else rituals.build_full_brief_speech(data, USER_NAME, dashboard=_dash)
                else:
                    _full_brief = rituals.build_full_brief_speech(data, USER_NAME, dashboard=_dash)
                instruction = None
                _kind = "morning ritual"
            else:
                instruction = briefing.format_briefing(data, USER_NAME)
                _kind = "daily briefing"
        except Exception as e:
            logger.warning(f"briefing gather failed, skipping briefing: {e}")
            instruction = None
            _kind = None
        if _full_brief:
            # Morning ritual: ONE hard-muted monologue, no model narration -> echo-proof
            # on speakerphone. Mute covers the whole delivery so her TTS can't be heard.
            try:
                mic_control.mute_for(min(180.0, len(_full_brief) / 12.0 + 8.0))
            except Exception:
                pass
            await worker.queue_frames([TTSSpeakFrame(_full_brief)])
            logger.info(f"{_kind} (one-block monologue, hard-muted) delivered")
        elif instruction:
            context.add_message({"role": _instr_role, "content": instruction})
            await worker.queue_frames([LLMRunFrame()])
            logger.info(f"{_kind} (code-orchestrated) delivered")

            # Proactive strategist (morning only): hand a CAPABLE agent (Hermes/codex)
            # the goals + today's real context and speak back the day's highest-leverage
            # moves — the reasoning the small voice model can't do. Runs in the BACKGROUND
            # with a hard timeout so it can never hang the ritual; lands as a spoken
            # follow-up once the agent answers. Off via JARVIS_MORNING_STRATEGY=0.
            if _kind == "morning ritual" and os.getenv("JARVIS_MORNING_STRATEGY", "1") == "1":

                async def _deliver_strategy(_data=data, _dash=_dash):
                    try:
                        import agent_bridge

                        # Ground the strategy in the owner's OWN knowledge (wiki + Obsidian).
                        knowledge = ""
                        try:
                            from morning_brief_tool import _wiki_knowledge

                            knowledge = await _wiki_knowledge()
                        except Exception as e:
                            logger.debug(f"strategy knowledge fetch skipped: {e}")
                        task_text = rituals.build_strategy_task(
                            _data, USER_NAME, dashboard=_dash, knowledge=knowledge
                        )
                        plan = await agent_bridge.run_agent_task(
                            task_text,
                            timeout_s=float(
                                os.getenv("JARVIS_MORNING_STRATEGY_TIMEOUT_S", "45")
                            ),
                        )
                        if not plan:
                            return
                        # Keep the FULL deep version in a dated .md for reference;
                        # EVE only speaks the concise gist (hermes can run long).
                        try:
                            rituals.save_strategy(plan, USER_NAME)
                        except Exception as e:
                            logger.debug(f"strategy .md save skipped: {e}")
                        # Don't speak into a session that was replaced/ended while the
                        # agent was thinking.
                        if _current_session is not session_task or session_task.done():
                            return
                        spoken = plan.strip()
                        if len(spoken) > 900:  # cap the spoken part; full text is in the .md
                            spoken = spoken[:900].rsplit(" ", 1)[0] + " — the rest is saved in your notes."
                        _line = f"Alright. Here's where to put your energy today. {spoken}"
                        try:  # hard-mute so the strategy block can't echo either
                            mic_control.mute_for(min(180.0, len(_line) / 12.0 + 8.0))
                        except Exception:
                            pass
                        await worker.queue_frames([TTSSpeakFrame(_line)])
                        logger.info("morning strategy delivered (proactive)")
                    except Exception as e:
                        logger.warning(f"morning strategy skipped: {e}")

                asyncio.create_task(_deliver_strategy())
    elif os.getenv("JARVIS_BOOT_GREETING", "0") == "1":
        context.add_message(
            {
                "role": _instr_role,
                "content": (
                    f"{USER_NAME} just connected from his phone. Greet him in ONE short sentence — "
                    "phone link is live, what does he need."
                ),
            }
        )
        await worker.queue_frames([LLMRunFrame()])
    else:
        logger.info("Phone connect greeting disabled (JARVIS_BOOT_GREETING=0) — EVE waits for you to speak")

    async def skill_feed_watcher():
        # Live "Use now" feeds (app Skills tab): claim, speak via this session's announce()
        # (which raises when the session goes stale — caught here), then mark delivered. One
        # bad tick logs and the loop continues. NOTE: phone announce has no bridge.busy quiet
        # gate (single-user phone session), so a live feed may land mid-turn — acceptable.
        import skill_feed
        while True:
            try:
                messages, ids = await asyncio.to_thread(skill_feed.pending_live_messages)
                for msg in messages:
                    await announce(msg["content"])
                if ids:
                    await asyncio.to_thread(skill_feed.mark_delivered, ids)
            except Exception as e:
                logger.warning(f"skill_feed watcher tick failed: {e}")
            await asyncio.sleep(float(os.getenv("JARVIS_SKILL_FEED_POLL_S", "3")))

    async def agent_replay_watcher():
        # Delegated results that came home while no phone session was live get replayed
        # past-tense here. is_alive guards against speaking into a replaced session; even if it
        # slips through, this session's announce() raises "no longer live" and try_announce
        # catches it -> QUEUED, so the row stays unspoken (delivered_at NULL) for the next session.
        # Talk-back parity with bot.py (§4.3): undelivered BLOCKERS and every still-unanswered
        # QUESTION resurface here too — the phone is exactly where the away-owner answers from
        # ("answer hermes: ..." -> resume_delegate -> the shared answer store).
        import time as _time

        import agent_delivery
        import agent_tasks
        import try_announce
        alive = lambda: _current_session is session_task and not session_task.done()

        # Detached jarvis_agent seam (same contract as bot.py): a hand-off from the
        # phone must not tie EVE up; the result speaks through try_announce when it lands.
        import agent_bridge as _agent_bridge

        async def _detached_announce(instruction, cid):
            return await try_announce.deliver(announce, instruction, cid=cid, is_alive=alive)

        _agent_bridge.set_detached_announce(_detached_announce)
        poll_s = float(os.getenv("JARVIS_AGENT_REPLAY_POLL_S", "5"))
        _asked_qids: set = set()
        _notify_backoff: dict = {}
        while True:
            try:
                for row in await asyncio.to_thread(agent_tasks.claim_replays):
                    await try_announce.deliver(
                        announce, try_announce.replay_instruction(row),
                        cid=row["id"], is_alive=alive)
                now = _time.monotonic()
                for row in await asyncio.to_thread(agent_tasks.failed_replays):
                    if now - _notify_backoff.get(row["id"], 0.0) < 60.0:
                        continue
                    _notify_backoff[row["id"]] = now
                    await agent_delivery.deliver_update(
                        row, announce=announce, broadcast=bridge.broadcast, is_alive=alive)
                for row in await asyncio.to_thread(agent_tasks.list_awaiting):
                    q = row.get("question") or {}
                    qid = q.get("qid")
                    if not qid or qid in _asked_qids:
                        continue
                    if _time.time() - float(q.get("asked_at") or 0.0) < 2 * poll_s:
                        continue                      # fresh ask: the desktop seam has it
                    st = await agent_delivery.deliver_update(
                        row, announce=announce, broadcast=bridge.broadcast, is_alive=alive)
                    if st in (agent_delivery.SPOKEN, agent_delivery.NOTIFIED):
                        _asked_qids.add(qid)
            except Exception as e:
                logger.warning(f"agent replay tick failed: {e}")
            await asyncio.sleep(poll_s)

    # His LiveKit transport has no pc_id attribute (smallwebrtc does) — getattr keeps both safe.
    logger.info(f"phone session started (pc_id={getattr(connection, 'pc_id', 'livekit')})")
    runner = WorkerRunner(handle_sigint=False)
    await runner.add_workers(worker)
    skill_feed_task = asyncio.create_task(skill_feed_watcher())
    agent_replay_task = asyncio.create_task(agent_replay_watcher())
    try:
        await runner.run()
    finally:
        # Cancel the watchers FIRST — phone sessions are replaced on every reconnect, so an
        # uncancelled poller would leak one orphan per reconnect (BMAD: Winston).
        for _t in (skill_feed_task, agent_replay_task):
            _t.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await _t
        # Stop this session's reminder timers so they can't fire into a dead
        # pipeline; the records stay on disk for the desktop loop's pickup.
        reminders.cancel_all()
        logger.info("phone session ended")


if __name__ == "__main__":
    from pipecat.runner.run import main

    preload_models()

    # Default to the same host/port jarvis-up and tailscale serve expect,
    # while still allowing explicit CLI overrides.
    if len(sys.argv) == 1:
        sys.argv += [
            "--host", os.getenv("JARVIS_PHONE_HOST", "127.0.0.1"),
            "--port", os.getenv("JARVIS_PHONE_PORT", "8788"),
            "-t", "webrtc",
        ]
    main()
