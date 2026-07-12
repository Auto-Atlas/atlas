#
# Jarvis sidecar — full-duplex local voice loop on top of the openjarvis shell.
#
# Default mode is LOCAL: faster-whisper (GPU) -> Ollama 8B -> Kokoro TTS.
# No API keys, $0/min, runs entirely on your RTX 5070.
#
# Set JARVIS_MODE=showtime to swap to cloud (Deepgram + OpenAI + Cartesia) for
# the "wow" demo. The metrics bridge streams per-turn latency/usage + transcripts
# over a localhost WebSocket so the openjarvis X-Ray footer can render them live.
#
# Run on WINDOWS-NATIVE Python (not WSL) so it owns the mic/speakers and the GPU.
#

import asyncio
import contextlib
import json
import os
import socket
import sys
import time
from pathlib import Path

# faster-whisper (CTranslate2) loads cublas/cudnn from the nvidia pip wheels.
# Register their bin dirs explicitly so GPU STT works no matter how this
# process is launched (run.bat, Start-Process, Tauri spawn, ...). Without this,
# a launcher with a bare PATH hits "cublas64_12.dll is not found" and STT dies.
# CPU fallback is NOT acceptable here (it pegs the machine) — fail loudly instead.
if sys.platform == "win32":
    _nvidia_root = Path(__file__).parent / ".venv" / "Lib" / "site-packages" / "nvidia"
    for _bin in sorted(_nvidia_root.glob("*/bin")):
        os.add_dll_directory(str(_bin))
        os.environ["PATH"] = f"{_bin};{os.environ.get('PATH', '')}"

# .env must be loaded BEFORE the lock below (JARVIS_LOCK_PORT is a .env knob —
# reading it pre-dotenv silently pinned every instance to 8764) and before the
# project imports further down: sms_tool, contacts, weather_tool et al. read
# os.getenv at import time. dotenv itself is a cheap import; the heavy stack
# still loads after the lock.
from dotenv import load_dotenv

load_dotenv(override=True)

import atlas_env

atlas_env.apply_aliases()  # ATLAS_* public names fan into EVE_*/JARVIS_*

# Single-instance guard, taken BEFORE the heavy imports below (~7s of startup)
# and before any audio device is opened. jarvis-up's "is 8765 listening?" check
# has a window exactly that wide: two invocations inside it would both launch,
# fight over the mic/speakers, and the loser would crash AFTER grabbing audio.
# Holding a lock port for the life of the process closes the window.
_INSTANCE_LOCK = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    _INSTANCE_LOCK.bind(("127.0.0.1", int(os.getenv("JARVIS_LOCK_PORT", "8764"))))
    _INSTANCE_LOCK.listen(1)
except OSError:
    print(
        "jarvis-sidecar: another instance already holds the lock port — exiting.",
        file=sys.stderr,
    )
    sys.exit(111)

from loguru import logger

from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.frames.frames import LLMRunFrame
from pipecat.pipeline.pipeline import Pipeline
from pipecat.pipeline.worker import PipelineParams, PipelineWorker
from pipecat.processors.aggregators.llm_response_universal import (
    LLMContextAggregatorPair,
    LLMUserAggregatorParams,
)
from pipecat.transports.local.audio import LocalAudioTransport, LocalAudioTransportParams
from pipecat.workers.runner import WorkerRunner

from pipecat.frames.frames import (
    BotStartedSpeakingFrame,
    BotStoppedSpeakingFrame,
    InputAudioRawFrame,
)
from pipecat.processors.frame_processor import FrameProcessor

from bridge import MetricsBridge, MetricsWSObserver
from inbox_tool import check_inbox
from jarvis_core import build_context, register_tools, trim_messages
from persona import USER_NICK, USER_NAME
from reminders_tool import ReminderService
from sales_coach import try_load_business_context
from voice_llm import active_profile, instr_role_for, make_voice_llm
from weather_tool import fetch_weather

logger.remove(0)
logger.add(sys.stderr, level=os.getenv("LOG_LEVEL", "INFO"))

MODE = os.getenv("JARVIS_MODE", "local").lower()
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:8b")

# Injected instructions (boot brief, SMS announces, reminders) ride as
# trailing SYSTEM messages on Ollama — qwen3 there treats user turns as an
# invitation to deliberate. vLLM is the opposite: its Qwen3.5 chat template
# 400s on a conversation with no user message, and thinking is already off
# via chat_template_kwargs, so USER role is both required and safe.
# The injected-message role lives in voice_llm.py (instr_role_for) so bot.py and
# phone_bot.py share one source of truth; each session pins it to its brain profile.


# --- Half-duplex mic gate ---------------------------------------------------
# Without acoustic echo cancellation, the mic hears the speakers and EVE
# answers herself in a loop ("I'm here too" x forever, observed 2026-06-12).
# JARVIS_HALF_DUPLEX=1 drops mic audio while the bot is speaking, plus a tail
# for room reverb. Trade-off: barge-in is disabled — you can't talk over her.
# Once AEC is active (PipeWire echo-cancel + libwebrtc-audio-processing1),
# set it back to 0 for full duplex.
class MicGate(FrameProcessor):
    def __init__(self, tail_s: float, max_gate_s: float = 30.0):
        super().__init__()
        self._tail_s = tail_s
        self._max_gate_s = max_gate_s
        self._bot_speaking = False
        self._open_after = 0.0
        self._gate_deadline = 0.0  # watchdog: hard ceiling on a single closed-gate stretch

    async def process_frame(self, frame, direction):
        await super().process_frame(frame, direction)
        if isinstance(frame, BotStartedSpeakingFrame):
            self._bot_speaking = True
            # Arm the watchdog: if BotStoppedSpeaking is ever dropped, the gate would
            # otherwise stay closed forever and deafen EVE. Force it open after the ceiling.
            self._gate_deadline = time.monotonic() + self._max_gate_s
        elif isinstance(frame, BotStoppedSpeakingFrame):
            self._bot_speaking = False
            self._open_after = time.monotonic() + self._tail_s
        if isinstance(frame, InputAudioRawFrame):
            now = time.monotonic()
            # Watchdog tripped: a speaking stretch ran past the ceiling -> assume the stop
            # frame was lost, reset state, and let the mic through so EVE isn't stuck deaf.
            if self._bot_speaking and now >= self._gate_deadline:
                logger.warning(
                    f"MicGate watchdog: bot 'speaking' > {self._max_gate_s:.0f}s with no "
                    "stop frame — force-opening mic (dropped BotStoppedSpeakingFrame?)"
                )
                self._bot_speaking = False
                self._open_after = 0.0
            elif self._bot_speaking or now < self._open_after:
                return  # mic is gated while EVE talks
        await self.push_frame(frame, direction)






def build_services():
    """Return (stt, llm, tts) for the active mode. Local needs no API keys."""
    if MODE == "showtime":
        from pipecat.services.cartesia.tts import CartesiaTTSService
        from pipecat.services.deepgram.stt import DeepgramSTTService
        from pipecat.services.openai.llm import OpenAILLMService

        stt = DeepgramSTTService(api_key=os.environ["DEEPGRAM_API_KEY"])
        llm = OpenAILLMService(
            api_key=os.environ["OPENAI_API_KEY"],
            settings=OpenAILLMService.Settings(
                model=os.getenv("SHOWTIME_LLM_MODEL", "gpt-4o"),
            ),
        )
        tts = CartesiaTTSService(
            api_key=os.environ["CARTESIA_API_KEY"],
            settings=CartesiaTTSService.Settings(
                voice=os.getenv("CARTESIA_VOICE", "71a7ad14-091c-4e8e-a314-022ece01c121"),
            ),
        )
        logger.info("MODE=showtime  (Deepgram STT / OpenAI LLM / Cartesia TTS)")
        logger.warning("MODE=showtime — Deepgram STT has no speaker-ID seam; speaker "
                       "gating is INACTIVE, demo runs owner-trusted.")
        return stt, llm, tts

    # ---- LOCAL (default) ----
    from pipecat.services.kokoro.tts import KokoroTTSService
    from pipecat.services.whisper.stt import WhisperSTTService

    # device="auto" already selects CUDA on the 5070; force it explicitly here.
    # Speaker ID rides the STT seam: when enabled, mix SpeakerIDMixin into Whisper so
    # each utterance sets the current speaker's tier before any tool can fire. When
    # disabled, build the plain class so the embedding code never enters the pipeline.
    _stt_kwargs = dict(
        device=os.getenv("WHISPER_DEVICE", "cuda"),
        compute_type=os.getenv("WHISPER_COMPUTE", "float16"),
    )
    if os.getenv("EVE_SPEAKER_ID", "enabled") == "disabled":
        stt = WhisperSTTService(**_stt_kwargs)
        logger.warning("EVE_SPEAKER_ID=disabled — speaker gating inactive (all unknown)")
    else:
        try:
            import speaker_id
            from speaker_stt import SpeakerIDMixin

            class SpeakerAwareWhisperSTT(SpeakerIDMixin, WhisperSTTService):
                pass

            stt = SpeakerAwareWhisperSTT(**_stt_kwargs)
            speaker_id.preload()
        except ImportError as e:
            # Default-on feature must not brick EVE if the dep isn't installed yet.
            # Fall back to plain Whisper (fail-closed: gating inactive -> everyone
            # 'unknown' unless the unsafe hatch is set). Loud, actionable warning.
            logger.warning(f"speaker-ID deps missing ({e}); plain Whisper, gating "
                           "INACTIVE. Run: pip install resemblyzer")
            stt = WhisperSTTService(**_stt_kwargs)
    # Resolve the brain profile ONCE per session so the LLM and every injected
    # instruction's role agree; a mid-session voice_brain switch applies on restart.
    _session_profile = active_profile()
    llm = make_voice_llm(_session_profile)
    _instr_role = instr_role_for(_session_profile)
    # The markdown filter is load-bearing for voice: qwen3 sneaks **bold**
    # past the no-markdown prompt rule and the TTS reads "asterisk asterisk"
    # out loud. Strip formatting between the LLM and the mouth.
    from pipecat.utils.text.markdown_text_filter import MarkdownTextFilter

    # TTS: kokoro (in-process, instant, preset voices) or chatterbox (the
    # local Chatterbox-Turbo server on :8004 — OpenAI-compatible, beats
    # ElevenLabs in blind tests, supports voice cloning + [laugh]/[sigh] tags).
    tts_engine = os.getenv("JARVIS_TTS", "kokoro").lower()
    if tts_engine == "chatterbox":
        from chatterbox_tts import ChatterboxTTSService

        tts = ChatterboxTTSService(
            api_key=os.getenv("JARVIS_TTS_API_KEY", "not-needed"),
            base_url=os.getenv("JARVIS_TTS_BASE_URL", "http://127.0.0.1:8004/v1"),
            voice=os.getenv("JARVIS_TTS_VOICE", "Emily.wav"),
            model=os.getenv("JARVIS_TTS_MODEL", "tts-1"),
            speed=float(os.getenv("JARVIS_TTS_SPEED", "1.0")),
            text_filters=[MarkdownTextFilter()],
        )
    else:
        tts = KokoroTTSService(
            settings=KokoroTTSService.Settings(
                voice=os.getenv("KOKORO_VOICE", "af_heart"),
            ),
            text_filters=[MarkdownTextFilter()],
        )
    logger.info(
        f"MODE=local  (faster-whisper / {_session_profile['api']} / {tts_engine})  — $0/min"
    )
    return stt, llm, tts


async def main():
    # Metrics + transcript bridge to the openjarvis UI (localhost WebSocket).
    bridge = MetricsBridge(
        host=os.getenv("JARVIS_WS_HOST", "127.0.0.1"),
        port=int(os.getenv("JARVIS_WS_PORT", "8765")),
        mode=MODE,
    )
    await bridge.start()

    transport = LocalAudioTransport(
        LocalAudioTransportParams(
            audio_in_enabled=True,
            audio_out_enabled=True,
        )
    )

    stt, llm, tts = build_services()

    # Persona + memory pack + the full tool registry, shared with the phone
    # body via jarvis_core. protected_head marks the boot block that must
    # survive every context trim.
    context, protected_head = build_context()

    # Sales coach: the owner-written business pack, loaded once at boot.
    # A fresh install has no pack; the coach tools refuse until one exists.
    business_pack = try_load_business_context()
    if business_pack is None:
        logger.warning("Sales coach ungrounded: no business pack (see EVE_BUSINESS_CONTEXT) - coach tools will refuse until you write one")
    else:
        logger.info(f"Sales coach grounded: business pack ({len(business_pack)} chars)")

    # Reminder timers fire through the same announce path as incoming texts;
    # constructed here so handlers exist before registration, started after
    # announce() is defined below.
    reminders = ReminderService(lambda text: announce(text))

    register_tools(llm, context, business_pack, reminders, bridge=bridge)

    # Boot tier. The STT seam sets the live speaker per utterance; this only covers
    # the window before the first utterance and the gating-off paths. Showtime has no
    # speaker-ID seam, so it runs owner-trusted (a curated demo). Otherwise the
    # self-closing unsafe hatch lets a not-yet-enrolled single user behave as today.
    import speaker_id
    import speaker_state
    if MODE == "showtime":
        speaker_state.set_current(USER_NAME, "owner", 1.0)
    else:
        profiles_present = bool(speaker_id.load_profiles(
            Path(os.getenv("EVE_VOICEPRINTS",
                           str(Path.home() / "eve-voiceprints" / "profiles.json")))))
        if speaker_state.boot_default_tier(profiles_present) == "owner":
            speaker_state.set_current(USER_NAME, "owner", 1.0)
            logger.warning("UNSAFE: no profiles enrolled — treating every voice as "
                           "owner. Enroll to engage gating.")

    user_aggregator, assistant_aggregator = LLMContextAggregatorPair(
        context,
        user_params=LLMUserAggregatorParams(vad_analyzer=SileroVADAnalyzer()),
    )

    mic_chain = [transport.input()]
    if os.getenv("JARVIS_HALF_DUPLEX", "0") == "1":
        mic_chain.append(MicGate(tail_s=float(os.getenv("JARVIS_MIC_GATE_TAIL", "0.5"))))
        logger.info("Half-duplex mic gate ON — barge-in disabled until AEC is installed")

    # Silence mode: keep EVE completely quiet until the OWNER says a wake phrase, then engage
    # for a grace window. The gate is transcription-level (AFTER stt, BEFORE the aggregator) so
    # a dropped utterance is still archived at the observer (which taps stt's push upstream of
    # here) but withheld from the LLM. Always in the pipeline; the LIVE setting controls it
    # (a no-op when silence is OFF), so a mid-session flip takes effect without a restart. This
    # is DESKTOP-only — phone_bot/watch_bot are deliberate-open surfaces (see silence_mode.py).
    import silence_mode
    silence = silence_mode.SilenceController()

    pipeline = Pipeline(
        [
            *mic_chain,          # mic in (gated while bot speaks if half-duplex)
            stt,                 # speech -> text
            silence_mode.SilenceGate(silence),  # silence mode: drop non-wake turns while quiet
            user_aggregator,     # add user turn to context
            llm,                 # think
            tts,                 # text -> speech
            transport.output(),  # speakers out
            assistant_aggregator,  # add bot turn to context
        ]
    )

    worker = PipelineWorker(
        pipeline,
        params=PipelineParams(
            enable_metrics=True,
            enable_usage_metrics=True,
        ),
        observers=[MetricsWSObserver(bridge)],
        # Always-on assistant: never self-cancel after silence. Pipecat's
        # default kills the pipeline after 5 quiet minutes, which looked like
        # random "disconnected" drops until the watchdog revived it.
        idle_timeout_secs=None,
    )

    # Kick off with a grounded morning brief. The data is fetched HERE, in
    # Python, not by the model: an 8B model reliably narrates results it is
    # handed, but asked to chain tool calls at boot it hallucinates fake JSON
    # and invented weather. Fetch real, inject, let it only verbalize.
    #
    # Cooldown: a process that restarted within the last ~10 minutes greets
    # SILENTLY. Without this, a crash loop becomes a greeting spammer — on
    # 6/10 the day's transcript was mostly ~16 boot briefs talking to nobody,
    # each burning GPU and growing the UI transcript.
    # Proactive greeting is OFF by default: the owner asked not to be greeted on every
    # (re)connect — "she'll speak when we speak anyways." A reconnect/watchdog-revive must be
    # silent; EVE only talks in response. Set JARVIS_BOOT_GREETING=1 to restore the morning brief.
    boot_greeting_enabled = os.getenv("JARVIS_BOOT_GREETING", "0") == "1"
    greeting_stamp = Path(__file__).parent / ".last_greeting"
    cooldown_s = float(os.getenv("JARVIS_GREETING_COOLDOWN", "600"))
    try:
        recently_greeted = (time.time() - greeting_stamp.stat().st_mtime) < cooldown_s
    except OSError:
        recently_greeted = False
    if not boot_greeting_enabled:
        logger.info("Proactive boot greeting disabled (JARVIS_BOOT_GREETING=0) — EVE speaks when spoken to")
    elif recently_greeted:
        logger.info("Boot brief skipped — greeted under 10 minutes ago (watchdog revival stays quiet)")
    else:
        boot_inbox = check_inbox(False)
        try:
            boot_weather = await fetch_weather()
        except Exception as e:
            boot_weather = {"ok": False, "error": str(e)}
        # Role choice is engine-specific — pinned to this session's brain profile.
        context.add_message(
            {
                "role": _instr_role,
                "content": (
                    "You just came online. Real data was fetched for you this second — "
                    f"INBOX: {json.dumps(boot_inbox)} WEATHER: {json.dumps(boot_weather)} — "
                    f"greet {USER_NICK} with a brief of at most two short sentences covering "
                    "only that data: anything new in the inbox, and today's weather. If a "
                    "fetch failed, mention it in passing. Do not mention meetings, "
                    "calendars, email, or system status — you have not checked those. "
                    "Plain spoken sentences only; never read out JSON or code."
                ),
            }
        )
        speaker_state.set_current(USER_NAME, "owner", 1.0)  # system/owner-originated turn
        await worker.queue_frames([LLMRunFrame()])
        # Stamp only once the brief is actually queued — touching first meant
        # a crash here suppressed the greeting for the next 10 minutes.
        greeting_stamp.touch()

    # Incoming-SMS webhook: the phone POSTs received texts here (tailnet).
    # Texts from others get announced out loud through the same path as the
    # boot brief; "jarvis ..." self-texts are the remote command channel.
    from sms_webhook import start_webhook_server

    # keep=30: the standing prompt (persona + memory + 23 tool schemas) is
    # already ~3.9k of the 8192-token window; 40 kept messages could push a
    # long tool-heavy stretch past it, and Ollama truncates from the TOP —
    # the system prompt — without saying so.
    def trim_context(max_messages: int = 50, keep: int = 30) -> None:
        # The context grows for the life of the process; a stable always-on
        # session would eventually push the system prompt out of the model's
        # window. trim_messages keeps the WHOLE boot block (persona AND the
        # memory pack + primed skills) plus the newest turns, cutting only at a
        # safe boundary (never leading with an orphaned tool result).
        msgs = context.get_messages()
        trimmed = trim_messages(msgs, protected_head, max_messages, keep)
        if len(trimmed) == len(msgs):
            return
        context.set_messages(trimmed)
        logger.info(f"Context trimmed: {len(msgs)} -> {len(trimmed)} messages")

    announce_lock = asyncio.Lock()

    async def announce(instruction: str):
        # Serialized, and gated on a quiet pipeline: an announcement landing
        # mid-turn used to mutate the context during a live LLM run and queue
        # a second generation on top of it. Capped wait — never starve.
        async with announce_lock:
            # Silence-mode HOLD: while she's quiet, proactive/injected speech (reminders,
            # initiative, calendar, agent results, skill feed, inbound-SMS announce) is HELD
            # here — holding announce_lock preserves arrival order (fair FIFO), and because we
            # have NOT returned, callers haven't marked anything delivered, so a held item stays
            # in its durable store and re-surfaces after a crash (queued, not spoken, not lost).
            # Released on the next owner wake (gate opens) or when silence mode is turned off.
            await silence.wait_until_open()
            for _ in range(60):
                if not bridge.busy:
                    break
                await asyncio.sleep(0.5)
            trim_context()
            # Engine-specific role on purpose — pinned to this session's brain profile.
            context.add_message({"role": _instr_role, "content": instruction})
            # System/owner-originated turn (reminder fire, inbound-SMS announce): no
            # utterance set a tier, so assert owner before the gate sees this turn.
            speaker_state.set_current(USER_NAME, "owner", 1.0)
            await worker.queue_frames([LLMRunFrame()])

    # Liveness flag for TOTAL result delivery (try_announce): a delegate callback that
    # arrives after this session tears down must NOT speak into a dead pipeline — it checks
    # _session.alive and queues the result for replay instead. Flipped False in the finally.
    import types
    _session = types.SimpleNamespace(alive=True)

    async def _agent_try_announce(instruction, cid):
        # Total delivery for delegate callbacks: speak if this session is live, else queue
        # the result for session-start replay (never raise into a torn-down pipeline).
        import try_announce
        return await try_announce.deliver(
            announce, instruction, cid=cid, is_alive=lambda: _session.alive)

    # Detached jarvis_agent (the owner, 2026-07-10): handing a task to claude code/codex
    # must not tie EVE up — the seam's presence flips the tool to hand-off-and-return,
    # with the result spoken through the same total-delivery path when it lands.
    import agent_bridge as _agent_bridge
    _agent_bridge.set_detached_announce(_agent_try_announce)

    # Questions already delivered this process run (qid set). Shared between the inbound
    # push seam (which delivers a question the moment it arrives) and the replay watcher
    # (which resurfaces unanswered ones) so a fresh ask is never spoken twice within seconds.
    _asked_qids: set = set()

    async def _agent_deliver_update(row, kind=None, text=None):
        # The inbound talk-back seam: the SAME shared delivery path the poller uses
        # (talk-back §4.3). A delivered question registers its qid so the replay watcher
        # doesn't repeat it moments later.
        import agent_delivery
        st = await agent_delivery.deliver_update(
            row, announce=announce, broadcast=bridge.broadcast,
            is_alive=lambda: _session.alive, kind=kind, text=text)
        if ((kind or "") == agent_delivery.AGENT_QUESTION
                or row.get("status") == "awaiting_user"):
            qid = (row.get("question") or {}).get("qid")
            if qid and st in (agent_delivery.SPOKEN, agent_delivery.NOTIFIED):
                _asked_qids.add(qid)
        return st

    webhook_runner = await start_webhook_server(
        announce, bridge.broadcast, try_announce_fn=_agent_try_announce,
        deliver_update_fn=_agent_deliver_update)

    async def skill_feed_watcher():
        # Live "Use now" feeds (app Skills tab): claim, speak via the same quiet-pipeline
        # announce() path as reminders, then mark delivered so 'delivered' means spoken. One
        # bad tick logs and the loop continues (never dies on a transient store error).
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

    skill_feed_task = asyncio.create_task(skill_feed_watcher())

    async def agent_replay_watcher():
        # Delegated results that came home while EVE was away (resolved but unspoken) get
        # replayed past-tense at the next chance. delivered_at is set only on a SPOKEN announce,
        # so a crashed tick re-surfaces the row (claim_replays keys on delivered_at IS NULL).
        # Talk-back §4.3 extends replay to undelivered BLOCKERS (failed rows) and to every
        # still-unanswered QUESTION — a pending question resurfaces until answered/terminal.
        import time as _time

        import agent_delivery
        import agent_tasks
        import delivery_policy
        import try_announce
        poll_s = float(os.getenv("JARVIS_AGENT_REPLAY_POLL_S", "5"))
        _notify_backoff: dict = {}   # cid -> last failed-replay attempt (>=60s between tries)
        while True:
            try:
                # Replay is SPEAKING — it holds through quiet hours (a queued 2am row used to
                # blurt into the night; now it waits and EVE wakes up WITH it in the morning).
                # This gate is also what lets unsolicited link rows stay unmarked overnight
                # (agent_delivery resurfacing discipline) without a failed-replay push storm.
                # Questions below are exempt: a blocked agent outranks the clock (§4.3).
                if not delivery_policy.in_quiet_hours():
                    for row in await asyncio.to_thread(agent_tasks.claim_replays):
                        await try_announce.deliver(
                            announce, try_announce.replay_instruction(row),
                            cid=row["id"], is_alive=lambda: _session.alive)
                    now = _time.monotonic()
                    for row in await asyncio.to_thread(agent_tasks.failed_replays):
                        if now - _notify_backoff.get(row["id"], 0.0) < 60.0:
                            continue
                        _notify_backoff[row["id"]] = now
                        await agent_delivery.deliver_update(
                            row, announce=announce, broadcast=bridge.broadcast,
                            is_alive=lambda: _session.alive)
                for row in await asyncio.to_thread(agent_tasks.list_awaiting):
                    q = row.get("question") or {}
                    qid = q.get("qid")
                    # Skip questions the push path just delivered (the seam registers the qid)
                    # and questions younger than 2 ticks (avoid double-speak on fresh asks).
                    if not qid or qid in _asked_qids:
                        continue
                    if _time.time() - float(q.get("asked_at") or 0.0) < 2 * poll_s:
                        continue
                    st = await agent_delivery.deliver_update(
                        row, announce=announce, broadcast=bridge.broadcast,
                        is_alive=lambda: _session.alive)
                    if st in (agent_delivery.SPOKEN, agent_delivery.NOTIFIED):
                        _asked_qids.add(qid)   # once per process run, and only if it landed
                await asyncio.to_thread(agent_tasks.reap_stale_resolving)
            except Exception as e:
                logger.warning(f"agent replay tick failed: {e}")
            await asyncio.sleep(poll_s)

    agent_replay_task = asyncio.create_task(agent_replay_watcher())

    async def calendar_watcher():
        # Proactive calendar surfacing (calendar_watch.py): event reminders + morning/evening
        # look-aheads, delivered like everything else — spoken live, pushed when away/quiet,
        # broadcast to the app. No cron subsystem: this loop IS the schedule. Off when
        # JARVIS_CALENDAR_ICS_URL is unset or EVE_CAL_WATCH=0.
        import calendar_watch
        import initiative
        if initiative.enabled():
            logger.info("calendar watcher stood down — initiative engine owns the calendar source")
            return
        if not calendar_watch.enabled():
            logger.info("calendar watcher disabled (no ICS url or EVE_CAL_WATCH=0)")
            return
        state = calendar_watch.WatchState()
        poll_s = float(os.getenv("EVE_CAL_POLL_S", "120"))
        logger.info(f"calendar watcher started (lead {os.getenv('EVE_CAL_LEAD_MIN', '15')}m, "
                    f"poll {int(poll_s)}s)")
        while True:
            try:
                await calendar_watch.tick(
                    state, announce=announce, broadcast=bridge.broadcast,
                    is_alive=lambda: _session.alive)
            except Exception as e:
                logger.warning(f"calendar watcher tick failed: {e}")
            await asyncio.sleep(poll_s)

    calendar_watch_task = asyncio.create_task(calendar_watcher())

    async def initiative_watcher():
        # Unified proactive surfacing (initiative.py): calendar nudges + daily rhythms +
        # important email, ONE speak-now decision (prefs, quiet hours, no-spam gap).
        # Same shape as the other watchers: one bad tick logs and the loop continues.
        import initiative
        if not initiative.enabled():
            logger.info("initiative engine disabled (EVE_INITIATIVE=0)")
            return
        state = initiative.EngineState()
        poll_s = float(os.getenv("EVE_INITIATIVE_POLL_S", "60"))
        logger.info(f"initiative engine started (poll {int(poll_s)}s, "
                    f"gap {os.getenv('EVE_INITIATIVE_MIN_GAP_S', '180')}s)")
        while True:
            try:
                await initiative.tick(
                    state, announce=announce, broadcast=bridge.broadcast,
                    is_alive=lambda: _session.alive)
            except Exception as e:
                logger.warning(f"initiative tick failed: {e}")
            await asyncio.sleep(poll_s)

    initiative_task = asyncio.create_task(initiative_watcher())

    async def agent_poller():
        # The SOLE executor of poll-delivery delegate tasks (no inline run anywhere else ->
        # a side-effecting one-shot is never sent twice). Each tick reaps dead leases, claims
        # pending tasks, runs each ONCE under its lease, resolves fenced + finishes, then
        # delivers live ("just finished") via the total announce path + an Activity receipt.
        from delegate_registry import poll_tick
        lease_s = float(os.getenv("EVE_DELEGATE_LEASE_S", "300"))
        hard_s = float(os.getenv("EVE_DELEGATE_HARD_S", "180"))

        async def deliver(row):
            # ONE shared delivery path (talk-back §4.3): quiet-hours notify (marked delivered
            # only iff a channel actually landed), live speak with kind-matched framing,
            # dead-session notify fallback, app broadcast — identical to the inbound push path.
            import agent_delivery
            await agent_delivery.deliver_update(
                row, announce=announce, broadcast=bridge.broadcast,
                is_alive=lambda: _session.alive)

        while True:
            try:
                await poll_tick(deliver, lease_s=lease_s, hard_s=hard_s)
            except Exception as e:
                logger.warning(f"agent poller tick failed: {e}")
            await asyncio.sleep(float(os.getenv("EVE_DELEGATE_POLL_S", "4")))

    agent_poller_task = asyncio.create_task(agent_poller())

    # Reload persisted reminders; anything that came due while the process
    # was down gets announced as missed instead of silently dropped.
    await reminders.start()

    runner = WorkerRunner(handle_sigint=False if sys.platform == "win32" else True)
    await runner.add_workers(worker)
    try:
        await runner.run()
    finally:
        _session.alive = False
        for _t in (skill_feed_task, agent_replay_task, calendar_watch_task,
                   agent_poller_task, initiative_task):
            _t.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await _t
        await webhook_runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
