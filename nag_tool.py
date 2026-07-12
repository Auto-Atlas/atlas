"""Close the ack loop — the voice tool behind "done with the dentist thing" / "stop
reminding me" / "ask me again in 30". nag_store holds the open items; the initiative
engine's nag_source re-surfaces them until this tool confirms complete (or snoozes).
Owner-gated (tool_policy.OWNER_ONLY): a guest must not silence the owner's open loops."""

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

COMPLETE_REMINDER_SCHEMA = FunctionSchema(
    name="complete_reminder",
    description=(
        "Mark an OPEN (resurfacing) reminder or calendar follow-up as DONE so EVE stops "
        "checking in about it — use when the user says 'done', 'that's handled', 'I did "
        "it', or 'stop reminding me about X'. With snooze_minutes it pauses instead "
        "('ask me again in 30'). NOT for cancelling a future one-shot reminder — that is "
        "cancel_reminder. TWO-STEP CONTRACT: if the result says nothing matched and "
        "lists `open` items, you MUST immediately call this tool again with the right "
        "item's id — never leave the item open after the user said it's done."),
    properties={
        "what": {"type": "string",
                 "description": "The user's words for which open item, e.g. 'the dentist "
                                "thing' (or an exact id after disambiguation)."},
        "snooze_minutes": {"type": "number",
                           "description": "Pause instead of complete: minutes until it may "
                                          "resurface again."},
    },
    required=["what"],
)


async def handle_complete_reminder(params: FunctionCallParams):
    import nag_store

    what = str(params.arguments.get("what", "")).strip()
    snooze_min = params.arguments.get("snooze_minutes")
    matches = nag_store.find(what)
    if not matches:
        open_now = nag_store.pending()
        await params.result_callback({
            "ok": False, "error": "nothing open matches that",
            "open": [{"what": x["what"], "id": x["id"]} for x in open_now[:8]],
            "instruction": ("Those exact words didn't match an open item, but YOU know "
                            "which one the user means from the conversation. If one of "
                            "the `open` items is it, IMMEDIATELY call complete_reminder "
                            "again with that item's id as `what` — do not answer the "
                            "user until the item is actually closed. Only if none of "
                            "them is what the user meant, say nothing is being "
                            "resurfaced by that name.")})
        return
    if len(matches) > 1:
        await params.result_callback({
            "ok": False, "needs_disambiguation": True,
            "matches": [{"what": x["what"], "id": x["id"]} for x in matches],
            "instruction": ("More than one open item matches. Ask WHICH one (name each by "
                            "its 'what', never an id out loud), then call complete_reminder "
                            "again with the chosen id.")})
        return
    rec = matches[0]
    if snooze_min is not None:
        nag_store.snooze(rec["id"], float(snooze_min))
        await params.result_callback({
            "ok": True, "snoozed": rec["what"], "minutes": float(snooze_min),
            "instruction": "Confirm in ONE short sentence when you'll check back on it."})
        return
    closed = nag_store.complete(rec["id"])
    await params.result_callback({
        "ok": bool(closed), "completed": rec["what"],
        "instruction": ("Confirm in ONE short sentence that it's done and you'll stop "
                        "checking in on it.")})
