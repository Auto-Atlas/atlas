# approval_push.py
#
# Self-hosted push for remote approvals (spec §2.2), no cloud account system:
#   - PRIMARY background wake: ntfy (self-hosted on the tailnet). Doze-safe; the
#     notification carries a "Review" action that OPENS the app to the primed approval
#     card (never a one-tap Approve from a pocket) and a "Deny" action.
#   - ACTIVE escalation: if ntfy is unreachable, escalate via the existing Telegram path
#     (OpenJarvis channel) so the owner is not silently missed.
# FCM is rejected — it needs a Google cloud project (an account system).
#
# notify() is best-effort and NEVER raises into its caller (tool_policy stages the draft
# whether or not the push lands; a failed push must not undo a persisted approval).
#
import os

import aiohttp
from loguru import logger

_TIMEOUT = aiohttp.ClientTimeout(total=8)


def _ntfy_url() -> str:
    base = os.getenv("EVE_NTFY_URL", "").rstrip("/")
    topic = os.getenv("EVE_NTFY_TOPIC", "eve-approvals")
    return f"{base}/{topic}" if base else ""


async def _publish_ntfy(summary: str, approval_id: str, *, title: str = "",
                        tags: str = "lock") -> bool:
    url = _ntfy_url()
    if not url:
        return False
    # ntfy "actions" header: a single Review action that deep-links the app to the primed approval
    # card. The Deny action was REMOVED: an unsigned, id-less `http` POST from a notification is an
    # unauthenticated path to deny a real approval (anyone who can reach the deny URL could fire it,
    # and the ntfy action carries no bearer). Denial must go through the app's authenticated
    # /v1/approvals/{id}/deny — reached by tapping Review, not a one-tap pocket action.
    app_scheme = os.getenv("EVE_APP_SCHEME", "eve://approvals")
    from persona import ASSISTANT_NAME  # configured self-name; EVE is a product
    headers = {
        "Title": title or f"{ASSISTANT_NAME} — approval needed",
        "Priority": "high",
        "Tags": tags,
        "Actions": f"view, Review, {app_scheme}/{approval_id}, clear=false",
    }
    try:
        async with aiohttp.ClientSession(timeout=_TIMEOUT) as s:
            async with s.post(url, data=summary.encode("utf-8"), headers=headers) as r:
                return 200 <= r.status < 300
    except Exception as e:
        logger.debug(f"ntfy publish failed: {e!r}")
        return False


async def _escalate_telegram(summary: str) -> bool:
    try:
        from openjarvis_client import OpenJarvisClient
        from persona import ASSISTANT_NAME
        await OpenJarvisClient().channel_send(
            "telegram", content=f"{ASSISTANT_NAME} needs your approval: {summary}"
        )
        return True
    except Exception as e:
        logger.debug(f"telegram escalation failed: {e!r}")
        return False


async def notify_event(summary: str, *, title: str, tags: str = "bell",
                       priority: str = "high") -> bool:
    """Generic informational push to the owner's phone over the SAME self-hosted ntfy
    channel approvals ride (so it works wherever approval pushes already reach — no
    Firebase/FCM required). No action buttons: this is a notification, not a decision.
    Best-effort, never raises — callers treat a failed push like a failed mirror leg."""
    url = _ntfy_url()
    if not url:
        return False
    headers = {"Title": title, "Priority": priority, "Tags": tags}
    try:
        async with aiohttp.ClientSession(timeout=_TIMEOUT) as s:
            async with s.post(url, data=summary.encode("utf-8"), headers=headers) as r:
                return 200 <= r.status < 300
    except Exception as e:
        logger.debug(f"ntfy event publish failed: {e!r}")
        return False


async def notify(summary: str, approval_id: str, *, title: str = "") -> dict:
    """Push an alert. Returns {'ntfy': bool, 'telegram': bool}. Never raises.
    Telegram is escalated ONLY if ntfy did not deliver (active safety net, spec §2.2).
    `title` names the notification honestly (agent question / calendar / blocker…);
    default keeps the original approval framing."""
    ntfy_ok = await _publish_ntfy(summary, approval_id, title=title)
    telegram_ok = False
    if not ntfy_ok:
        telegram_ok = await _escalate_telegram(summary)
    return {"ntfy": ntfy_ok, "telegram": telegram_ok}
