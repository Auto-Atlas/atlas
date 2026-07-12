# Email source: surfaces ONLY important triage keeps (VIP/Client/Hot Prospect/Billing),
# metadata only (never a body), respects the poll interval, dedupes via state.seen.
import asyncio
from datetime import datetime

import pytest

import initiative

NOW = datetime(2026, 7, 2, 10, 0, 0)


def _run(coro):
    return asyncio.run(coro)


def _msg(from_email, subject, **kw):
    return {"from": kw.get("name", from_email), "from_email": from_email,
            "subject": subject, "date": "Thu, 02 Jul 2026 09:5",
            "headers": kw.get("headers", {})}


@pytest.fixture(autouse=True)
def prio(monkeypatch):
    monkeypatch.delenv("EVE_INITIATIVE_EMAIL_POLL_S", raising=False)
    import priorities
    monkeypatch.setattr(priorities, "load", lambda: {
        "personal_emails": ["wife@example.com"],
        "important_domains": [], "operational_tools": [],
        "high_intent_keywords": ["ready to buy"], "ignore_keywords": ["newsletter"],
        "billing_security_keywords": []})


def test_only_important_mail_surfaces_and_is_fenced():
    st = initiative.EngineState()
    msgs = [
        _msg("wife@example.com", "dinner tonight"),                       # VIP -> surfaces
        _msg("stranger@x.com", "ready to buy 3 sites", name="Big Lead"),  # Hot Prospect
        _msg("rando@y.com", "hello there"),                               # Low Priority -> no
        _msg("spam@z.com", "our newsletter"),                             # dropped
    ]
    items = _run(initiative.email_source(st, NOW, 1000.0, messages=msgs))
    assert [i.source_ref for i in items] == ["wife@example.com", "stranger@x.com"]
    it = items[1]
    assert (it.source, it.kind, it.urgency) == ("email", "important_email", "med")
    assert "Hot Prospect" in it.headline and "ready to buy 3 sites" in it.headline
    assert "UNTRUSTED DATA" in it.instruction


def test_poll_interval_respected(monkeypatch):
    monkeypatch.setenv("EVE_INITIATIVE_EMAIL_POLL_S", "300")
    st = initiative.EngineState()
    msgs = [_msg("wife@example.com", "a")]
    assert len(_run(initiative.email_source(st, NOW, 1000.0, messages=msgs))) == 1
    # 10s later: interval not elapsed -> no poll, no items
    assert _run(initiative.email_source(st, NOW, 1010.0, messages=msgs)) == []
    # 400s later: polls again, but state.seen dedupes the same message
    st.seen.add("email:wife@example.com|a|Thu, 02 Jul 2026 09:5")
    assert _run(initiative.email_source(st, NOW, 1400.0, messages=msgs)) == []


def test_gmail_unconfigured_returns_empty(monkeypatch):
    import email_tool
    monkeypatch.setattr(email_tool, "GMAIL_USER", "")
    st = initiative.EngineState()
    assert _run(initiative.email_source(st, NOW, 1000.0)) == []
