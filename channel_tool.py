#
# Channels — push a message to one of the user's connected OpenJarvis channels
# (telegram, slack, etc.) via the local daemon. This is an EXTERNAL ACTION: a
# message leaves the machine and can't be unsent, so it's GATED like
# create_invoice — the first call previews it, Eve reads back channel + message,
# and it only sends when re-called with confirmed=true after the user agrees (the
# skill frontmatter routes it through the confirm gate). Failures are reported
# verbatim; we never fabricate a "sent".
#

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

from openjarvis_client import OpenJarvisClient

# Sent-once idempotency (mirrors sms_tool._sent_once / invoice_tool._created_once).
# send_to_channel is a needs_confirmation tool: tool_policy stages the call and a
# confirmed=true release sends it, single-fire PER STAGE. But the model can RE-STAGE
# the identical (channel, message) — a post-denial / threshold-lower retry, or a "do
# it again" turn — and confirm again, which re-POSTed to the channel: a duplicate
# message that can't be unsent. Both the in-context path (jarvis_core) and the headless
# release path (release.py) route through THIS handler, so guarding here covers both.
# Keying off (channel, message) makes a send fire AT MOST ONCE per draft; the key is
# added only AFTER channel_send succeeds, so a failed send stays re-sendable.
_sent_once: set[str] = set()


def _draft_key(channel: str, message: str) -> str:
    """Stable dedupe key for a staged channel send — there is no draft id, so the
    (channel, message) pair IS the identity of what gets sent."""
    return f"{channel}\x00{message}"


SEND_TO_CHANNEL_SCHEMA = FunctionSchema(
    name="send_to_channel",
    description=(
        "Push a message to one of the user's connected channels (e.g. 'telegram', "
        "'slack'), like an external send that can't be unsent. The FIRST call returns "
        "a preview of the channel and message — it does NOT send yet. Read the channel "
        "and exact message back to the user, and only after they clearly say yes, call "
        "send_to_channel AGAIN with confirmed set to true to actually send it."
    ),
    properties={
        "channel": {
            "type": "string",
            "description": "Which connected channel to send on, e.g. 'telegram' or 'slack'.",
        },
        "message": {
            "type": "string",
            "description": "The exact message text to send.",
        },
        "confirmed": {
            "type": "boolean",
            "description": (
                "Set true ONLY when re-calling after the user said yes to the "
                "previewed message. Omit or set false on the first call."
            ),
        },
    },
    required=["channel", "message"],
)


async def handle_send_to_channel(params: FunctionCallParams):
    channel = str(params.arguments.get("channel", "")).strip()
    message = str(params.arguments.get("message", "")).strip()
    if not channel or not message:
        await params.result_callback(
            {"ok": False, "error": "channel and message are required"}
        )
        return

    # Sent-once guard: if this exact (channel, message) already went out (e.g. the
    # model re-staged the same message after a denial and confirmed again), do NOT
    # re-send — report it was already delivered.
    key = _draft_key(channel, message)
    if key in _sent_once:
        logger.info(f"send_to_channel: already sent on {channel} — no re-send")
        await params.result_callback(
            {"ok": True, "already_sent": True, "channel": channel,
             "instruction": "That message was already sent — tell the user it's done; do not send it again."}
        )
        return

    try:
        await OpenJarvisClient().channel_send(channel, content=message)
    except Exception as e:
        logger.warning(f"send_to_channel failed: {e}")
        await params.result_callback(
            {"ok": False, "error": f"could not send to {channel}: {e}"}
        )
        return

    _sent_once.add(key)  # mark sent only AFTER the channel accepted it
    logger.info(f"send_to_channel sent on {channel} ({len(message)} chars)")
    await params.result_callback(
        {
            "ok": True,
            "sent": True,
            "channel": channel,
            "instruction": "Confirm to the user it was sent in one short sentence.",
        }
    )
