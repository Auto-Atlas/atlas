"""On-demand morning brief — the 5 AM ritual EVE runs when ASKED.

Same content as the automatic morning ritual (whys recited verbatim -> goals ->
real briefing -> wiki-grounded strategy), exposed as a voice tool so the user can SAY
"run my morning brief" and trigger the proactive loop on command — not only at 5 AM.
"""

from __future__ import annotations

import asyncio

from loguru import logger
from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.frames.frames import TTSSpeakFrame
from pipecat.services.llm_service import FunctionCallParams

import agent_bridge
import briefing
import mic_control
import rituals
from persona import USER_NAME

RUN_MORNING_BRIEF_SCHEMA = FunctionSchema(
    name="run_morning_brief",
    description=(
        "Deliver the user's full morning brief RIGHT NOW: recite their whys, name their goals, "
        "give today's real briefing (weather, email, calendar), then the day's highest-leverage "
        "moves toward their goals. Call this the moment they say 'run my morning brief', 'give "
        "me my rundown', 'run my day', 'do my morning ritual', or 'what's my brief'. Do not "
        "narrate anything yourself first — just call it."
    ),
    properties={},
    required=[],
)


def _strategy_query(dashboard: dict) -> str:
    """Build the wiki/memory search query from the user's OWN goals (life dashboard),
    so grounding follows whatever they actually set — no business or vertical assumed.
    Falls back to a generic phrasing when no goals are configured yet."""
    goals = dashboard.get("goals") or {}
    terms = [it.strip() for items in goals.values() for it in (items or []) if it and it.strip()]
    if terms:
        return "highest-leverage moves today toward these goals: " + "; ".join(terms)
    return "highest-leverage moves I could make today toward my goals"


async def _wiki_knowledge(query: str) -> str:
    """Pull relevant chunks from the user's OWN knowledge base (wiki + Obsidian) to
    ground the strategy. Best-effort: '' if OpenJarvis/memory is unavailable."""
    try:
        from openjarvis_client import OpenJarvisClient

        hits = await OpenJarvisClient().memory_search(query, top_k=4)
        return "\n".join(f"- {str(h.get('content', ''))[:300]}" for h in hits if h.get("content"))
    except Exception as e:
        logger.debug(f"morning brief: wiki knowledge fetch skipped: {e}")
        return ""


def _mute_est(text: str) -> float:
    """Seconds to speak ``text`` at ~12 chars/sec, plus a buffer, capped."""
    return min(150.0, len(text) / 12.0 + 8.0)


async def handle_run_morning_brief(params: FunctionCallParams):
    # Speakerphone has no echo cancellation: any gap in her speech lets the mic catch
    # her own TTS and spiral into a self-reply loop. So the brief is delivered as ONE
    # continuous monologue (no model narration turn) AND the mic is HARD-MUTED for the
    # whole delivery via mic_control — her voice physically can't be transcribed.
    try:
        data = await briefing.gather_briefing()
    except Exception as e:
        logger.warning(f"run_morning_brief: briefing gather failed ({e})")
        data = {}
    dash = rituals.load_dashboard()

    speech = rituals.build_full_brief_speech(data, USER_NAME, dashboard=dash)
    try:
        mic_control.mute_for(_mute_est(speech))
    except Exception:
        pass
    try:
        await params.llm.push_frame(TTSSpeakFrame(speech))
    except Exception as e:
        logger.debug(f"morning brief speak skipped: {e}")

    # STRATEGY — wiki-grounded, BACKGROUND with a hard timeout; one block, also
    # hard-muted while it speaks. Saved (+ filed to the wiki).
    async def _strategy():
        try:
            knowledge = await _wiki_knowledge(_strategy_query(dash))
            task = rituals.build_strategy_task(data, USER_NAME, dashboard=dash, knowledge=knowledge)
            plan = await agent_bridge.run_agent_task(task, timeout_s=45)
            if not plan:
                return
            try:
                rituals.save_strategy(plan, USER_NAME)
            except Exception:
                pass
            spoken = plan.strip()
            if len(spoken) > 900:
                spoken = spoken[:900].rsplit(" ", 1)[0] + " — the rest is in your notes."
            line = f"And here's where to put your energy today. {spoken}"
            try:
                mic_control.mute_for(_mute_est(line))
            except Exception:
                pass
            await params.llm.push_frame(TTSSpeakFrame(line))
            logger.info("on-demand morning strategy delivered")
        except Exception as e:
            logger.warning(f"on-demand strategy skipped: {e}")

    asyncio.create_task(_strategy())

    # No model narration turn — the whole brief was just spoken. Keep the model silent
    # so it can't add a line that echoes after the mute lifts.
    await params.result_callback(
        {
            "ok": True,
            "instruction": "The full morning brief was just spoken aloud to him in one "
            "continuous stretch. Say NOTHING else at all — do not summarize, greet, "
            "acknowledge, or add anything.",
        }
    )
