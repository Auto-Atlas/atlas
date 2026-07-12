"""Email triage — code-level, prompt-injection-safe filtering + ICP labeling.

Drops fluff and LABELS the rest by the owner's priorities (priorities.json): VIP /
Client / Hot Prospect / Tool-Billing-Security / Low Priority — and Ignore/Spam.
Real inbound leads (ICP high-intent language) get rescued and flagged; SEO /
crypto / recruiter / webinar noise gets dropped even if it slips past the bulk
headers.

SAFETY PROPERTY (injection-safe): classification uses ONLY metadata — the sender
address, a few standard headers (List-Unsubscribe, List-Id, Precedence,
Auto-Submitted), and keyword MATCHES against the (short) subject. The email BODY
is never fetched, never inspected, never returned to a model, and a keyword match
is code — not an LLM interpreting untrusted text. A hostile payload in a body
cannot reach the model through this path.

Pure functions, no I/O beyond reading priorities.json.
"""

from __future__ import annotations

import os
import re

import priorities as _priorities

# Sender local-parts that signal an unattended/automated mailbox.
_NOREPLY_RE = re.compile(
    r"(?:^|[._-])(?:no[-_.]?reply|do[-_.]?not[-_.]?reply|donotreply|mailer|mail|"
    r"notif(?:ication)?s?|alerts?|updates?|automated|bounce|postmaster)(?:[._-]|@)",
    re.I,
)


def _addr(msg: dict) -> str:
    return str(msg.get("from_email") or msg.get("from") or "").lower()


def _subject(msg: dict) -> str:
    return str(msg.get("subject") or "").lower()


def _any(hay: str, needles) -> bool:
    return any(n and n.lower() in hay for n in (needles or []))


def is_bulk(msg: dict) -> bool:
    """Marketing / mailing-list / bulk mail by its own standard headers."""
    h = {k.lower(): str(v or "") for k, v in (msg.get("headers") or {}).items()}
    if h.get("list-unsubscribe") or h.get("list-id"):
        return True
    prec = h.get("precedence", "").lower()
    if "bulk" in prec or "list" in prec or "junk" in prec:
        return True
    if h.get("auto-submitted", "").lower().startswith("auto"):
        return True
    return False


def is_noreply(msg: dict) -> bool:
    return bool(_NOREPLY_RE.search(_addr(msg)))


def _env_important() -> set[str]:
    raw = os.getenv("EVE_IMPORTANT_SENDERS", "")
    return {s.strip().lower() for s in raw.split(",") if s.strip()}


def classify(msg: dict, prio: dict) -> dict:
    """Return msg + {label, is_important, drop_reason}. drop_reason=None -> keep.

    Decision order (first match wins) — keeps important people no matter what,
    surfaces real leads, and only then drops noise:
      1. personal / important-domain / env-important  -> VIP|Client (keep)
      2. operational tool + billing/security subject  -> Tool/Billing/Security (keep)
      3. explicit ignore keyword in subject           -> DROP (Ignore/Spam)
      4. bulk headers / no-reply sender               -> DROP (marketing/automated)
      5. ICP high-intent language in subject          -> Hot Prospect (keep)
      6. otherwise (an unknown human, no signal)      -> Low Priority (keep)
    """
    addr = _addr(msg)
    subj = _subject(msg)
    hay_sender = f"{addr} {str(msg.get('from') or '').lower()}"

    personal = prio.get("personal_emails") or []
    domains = (prio.get("important_domains") or []) + list(_env_important())
    tools = prio.get("operational_tools") or []
    billing = prio.get("billing_security_keywords") or []
    ignore = prio.get("ignore_keywords") or []
    intent = prio.get("high_intent_keywords") or []

    def keep(label, important=False):
        return {**msg, "label": label, "is_important": important, "drop_reason": None}

    def drop(reason, label):
        return {**msg, "label": label, "is_important": False, "drop_reason": reason}

    # 1. people who always matter
    if _any(addr, personal):
        return keep("VIP", important=True)
    if _any(hay_sender, domains):
        return keep("Client", important=True)
    # 2. operational tools, but only when it's billing/security (not their marketing)
    if _any(addr, tools) and _any(subj, billing):
        return keep("Tool/Billing/Security", important=True)
    # 3. explicit noise wins even if it dodged the bulk headers
    if _any(subj, ignore):
        return drop("ignore/spam", "Ignore/Spam")
    # 4. standard marketing / automated mail
    if is_bulk(msg):
        return drop("bulk/marketing", "Low Priority")
    if is_noreply(msg):
        return drop("automated/no-reply", "Low Priority")
    # 5. a real (non-bulk) human speaking ICP language = a lead worth surfacing
    if _any(subj, intent):
        return keep("Hot Prospect", important=True)
    # 6. unknown human, no strong signal
    return keep("Low Priority")


def triage(messages: list[dict], prio: dict | None = None) -> tuple[list[dict], dict]:
    """Split unread into (kept_signal, dropped_summary).

    kept: important (VIP/Client/Tool-Billing/Hot Prospect) first, then the rest in
    original (newest-first) order. dropped: {reason: count} of what was hidden.
    """
    prio = _priorities.load() if prio is None else prio
    kept: list[dict] = []
    dropped: dict[str, int] = {}
    for m in messages:
        c = classify(m, prio)
        if c["drop_reason"]:
            dropped[c["drop_reason"]] = dropped.get(c["drop_reason"], 0) + 1
        else:
            kept.append(c)
    kept.sort(key=lambda m: not m["is_important"])  # important first; stable otherwise
    return kept, dropped
