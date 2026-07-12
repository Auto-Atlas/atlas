"""EVE on the Jetson body — a third body beside bot.py / phone_bot.py.

Reuses jarvis_core (persona/tools/memory) verbatim so the bodies can't drift.
Riva STT/TTS via the speech_factory switch; local mic+speaker via
LocalAudioTransport. APIs verified against phone_bot.main() (BMAD review).
"""
from __future__ import annotations
import asyncio
import os

from dotenv import load_dotenv
from loguru import logger
from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.pipeline.pipeline import Pipeline
from pipecat.processors.aggregators.llm_response_universal import (
    LLMContextAggregatorPair,
    LLMUserAggregatorParams,
)

from jarvis_core import build_context, register_tools
from speech_factory import _build_stt, _build_tts, MicGate, TrimmingAssistantAggregator
from reminders_tool import ReminderService
from sales_coach import try_load_business_context
from voice_llm import make_voice_llm

load_dotenv(override=True)

import atlas_env

atlas_env.apply_aliases()  # ATLAS_* public names fan into EVE_*/JARVIS_*


def build_brain(llm):
    """Assemble EVE's brain on `llm` (context + persona + gated tools) AND the
    user+assistant aggregators. Audio-free, so the whole pipeline-wiring seam is
    unit-tested. Returns (context, protected_head, user_aggr, assistant_aggr)."""
    context, protected_head = build_context()
    # None = no pack written yet; the coach tools refuse until one exists.
    business_pack = try_load_business_context()
    reminders = ReminderService(None)            # no announce path on the Jetson body
    register_tools(llm, context, business_pack, reminders, bridge=None)
    try:
        from jetson_tools import register_jetson_tools
        register_jetson_tools(llm, context)      # look + actuate_hand (Tasks 7/8)
    except ImportError:
        pass                                     # jetson_tools not present yet
    # VAD attaches to the USER AGGREGATOR (not the transport) — pipecat 1.3.0.
    user_aggr = LLMContextAggregatorPair(
        context,
        user_params=LLMUserAggregatorParams(vad_analyzer=SileroVADAnalyzer()),
    ).user()
    assistant_aggr = TrimmingAssistantAggregator(context, protected_head=protected_head)
    return context, protected_head, user_aggr, assistant_aggr


async def main():
    llm = make_voice_llm()                       # no args (voice_llm.py)
    context, protected_head, user_aggr, assistant_aggr = build_brain(llm)

    stt = _build_stt()
    tts = _build_tts()

    # Function-local: LocalAudioTransport hard-imports pyaudio at module top.
    from pipecat.transports.local.audio import (
        LocalAudioTransport,
        LocalAudioTransportParams,
    )
    in_dev = os.getenv("JARVIS_AUDIO_IN_DEVICE")
    out_dev = os.getenv("JARVIS_AUDIO_OUT_DEVICE")
    transport = LocalAudioTransport(LocalAudioTransportParams(
        audio_in_enabled=True, audio_out_enabled=True,
        audio_in_sample_rate=int(os.getenv("JARVIS_AUDIO_IN_RATE", "16000")),
        audio_out_sample_rate=int(os.getenv("JARVIS_AUDIO_OUT_RATE", "24000")),
        input_device_index=int(in_dev) if in_dev else None,
        output_device_index=int(out_dev) if out_dev else None,
        # NOTE: NO vad_analyzer here — TransportParams ignores it; VAD lives on
        # the user aggregator (build_brain).
    ))

    mic_in = [transport.input()]
    if os.getenv("JARVIS_PHONE_HALF_DUPLEX", "1") == "1":
        mic_in.append(MicGate(float(os.getenv("JARVIS_HALF_DUPLEX_TAIL_S", "0.6"))))
        logger.info("Half-duplex mic gate ON (Jetson) — EVE won't hear her own voice")

    # Assistant aggregator AFTER transport.output() so committed turns (and the
    # context trim) land back in the shared context (phone_bot.py ordering).
    pipeline = Pipeline([
        *mic_in, stt, user_aggr, llm, tts, transport.output(), assistant_aggr,
    ])

    from pipecat.pipeline.worker import PipelineParams, PipelineWorker
    from pipecat.workers.runner import WorkerRunner
    worker = PipelineWorker(pipeline, params=PipelineParams(
        enable_metrics=True, enable_usage_metrics=True,
        allow_interruptions=os.getenv("JARVIS_ALLOW_INTERRUPTIONS", "0") == "1",
    ))
    logger.info("EVE Jetson body up — Riva STT + TTS over local audio.")
    runner = WorkerRunner(handle_sigint=False)
    await runner.add_workers(worker)
    await runner.run()


if __name__ == "__main__":
    asyncio.run(main())
