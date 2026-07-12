"""Feedback loop for proactive surfacing — the voice tool behind "don't tell me these" /
"more of this". Owner-gated (tool_policy.OWNER_ONLY): guests must not reshape what EVE
chooses to say. The engine (initiative.py) reads the persisted prefs on every tick."""

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

import initiative

ADJUST_SURFACING_SCHEMA = FunctionSchema(
    name="adjust_surfacing",
    description=(
        "Tune EVE's proactive surfacing when the user gives feedback about unprompted "
        "updates. 'don't tell me these' / 'stop telling me about X' -> direction=mute (or "
        "'less' if they only want fewer). 'more of this' -> more. 'back to normal' -> "
        "reset. Sources: calendar (event nudges), rhythm (morning/evening/week briefs), "
        "email (important-mail alerts), nag (open-reminder check-ins)."),
    properties={
        "source": {"type": "string", "enum": list(initiative.KNOWN_SOURCES),
                   "description": "Which kind of surfacing the feedback is about."},
        "direction": {"type": "string", "enum": list(initiative.DIRECTIONS),
                      "description": "mute | less | more | reset"},
    },
    required=["source", "direction"],
)


async def handle_adjust_surfacing(params: FunctionCallParams):
    source = str(params.arguments.get("source", ""))
    direction = str(params.arguments.get("direction", ""))
    try:
        new = initiative.adjust(source, direction)
    except ValueError as e:
        await params.result_callback({"ok": False, "error": str(e)})
        return
    await params.result_callback({
        "ok": True, "source": source, **new,
        "instruction": (f"Confirm in one short, natural sentence what changed about "
                        f"{source} surfacing (it's persisted)."),
    })
