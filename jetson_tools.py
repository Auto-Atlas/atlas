"""Register Jetson-only tools (look, actuate_hand) on the llm through the same
tool_policy gate the rest of EVE uses. Kept OUT of jarvis_core so the brain
stays portable (no depthai/RUKA conditionals leak into the desktop/phone
bodies); these tools are body-local to the Jetson body.

Heavy deps (depthai via oakd_vision, the RUKA subprocess via hand_tool) are
imported lazily inside the handlers, so this module is import-clean off-Jetson.
"""
from __future__ import annotations

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.adapters.schemas.tools_schema import ToolsSchema

from tool_policy import policy, ToolPolicy
from toolguard import dedupe


LOOK_SCHEMA = FunctionSchema(
    name="look",
    description="Capture what EVE's OAK-D camera currently sees (RGB frame).",
    properties={},
    required=[],
)

HAND_SCHEMA = FunctionSchema(
    name="actuate_hand",
    description="Move EVE's robotic (RUKA) hand to a pose. High-risk physical action.",
    properties={
        "pose": {"type": "string", "enum": ["open", "close", "reset"],
                 "description": "The pose to move the hand to."},
        "hand": {"type": "string", "enum": ["right", "left"],
                 "description": "Which hand (default right)."},
        "confirmed": {"type": "boolean",
                      "description": "Set true on the confirmed call (the gate asks first)."},
    },
    required=["pose"],
)


def _make_look_handler():
    async def handle_look(params):
        from oakd_vision import OakDCamera  # lazy: depthai only on Jetson
        await params.result_callback(OakDCamera().capture())
    return handle_look


def _make_hand_handler():
    async def handle_actuate_hand(params):
        import hand_tool  # lazy: subprocess bridge into the ruka_hand env
        a = params.arguments or {}
        await params.result_callback(hand_tool.actuate(a.get("pose"), a.get("hand", "right")))
    return handle_actuate_hand


def register_jetson_tools(llm, context, extra_schemas=None):
    """Register the Jetson-only tools through policy()+dedupe() and widen the
    context tool list so the model can see them. Re-setting tools is additive
    and never touches the protected message head. The full schema list is
    assembled BEFORE the single set_tools so both tools are visible."""
    schemas = [LOOK_SCHEMA, HAND_SCHEMA] + list(extra_schemas or [])
    llm.register_function("look", dedupe("look",
        policy("look", ToolPolicy(risk_level="low"), _make_look_handler())))
    llm.register_function("actuate_hand", dedupe("actuate_hand",
        policy("actuate_hand",
               ToolPolicy(risk_level="high", needs_confirmation=True, requires_fields=("pose",)),
               _make_hand_handler())))
    from jarvis_core import ALL_TOOL_SCHEMAS
    context.set_tools(ToolsSchema(standard_tools=ALL_TOOL_SCHEMAS + schemas))
    return schemas
