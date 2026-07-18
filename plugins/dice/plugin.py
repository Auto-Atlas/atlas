# plugins/dice/plugin.py — the reference plugin, shipped so `plugins/` always
# demonstrates a working example. Copy this directory to start your own:
# one directory = one tool = one skill.md manifest (see plugins/README.md).
import random

from plugin_loader import plugin_tool


async def handle_roll_dice(params):
    try:
        sides = int(params.arguments.get("sides") or 6)
        count = int(params.arguments.get("count") or 1)
    except (TypeError, ValueError):
        await params.result_callback(
            {"ok": False, "error": "sides and count must be whole numbers"}
        )
        return
    if not (2 <= sides <= 1000) or not (1 <= count <= 20):
        await params.result_callback(
            {"ok": False, "error": "sides must be 2-1000 and count 1-20"}
        )
        return
    rolls = [random.randint(1, sides) for _ in range(count)]
    await params.result_callback(
        {"ok": True, "sides": sides, "rolls": rolls, "total": sum(rolls)}
    )


TOOLS = [
    plugin_tool(
        name="roll_dice",
        description="Roll one or more real dice and report the results.",
        properties={
            "sides": {"type": "integer", "description": "faces per die (default 6)"},
            "count": {"type": "integer", "description": "how many dice (default 1, max 20)"},
        },
        required=[],
        handler=handle_roll_dice,
    )
]
