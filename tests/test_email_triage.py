"""Email triage — injection-safe filtering + ICP labeling tests."""

from __future__ import annotations

import email_triage as et

PRIO = {
    "personal_emails": ["owner@atlas-owner.test"],
    "important_domains": ["roofco.com"],
    "operational_tools": ["stripe", "twilio"],
    "billing_security_keywords": ["invoice", "payment", "security alert"],
    "ignore_keywords": ["seo", "webinar", "crypto", "we're hiring", "recruiter"],
    "high_intent_keywords": ["missed calls", "more booked jobs", "automation", "lead follow"],
}


def _m(frm="A Person", addr="a@person.com", subject="hi", **headers):
    return {
        "from": frm, "from_email": addr, "subject": subject, "date": "",
        "headers": {k.replace("_", "-"): v for k, v in headers.items()},
    }


def _c(msg):
    return et.classify(msg, PRIO)


def test_vip_personal_email():
    r = _c(_m(addr="owner@atlas-owner.test", subject="note to self"))
    assert r["drop_reason"] is None and r["label"] == "VIP" and r["is_important"]


def test_client_important_domain():
    r = _c(_m(addr="boss@roofco.com", subject="contract"))
    assert r["label"] == "Client" and r["is_important"] and r["drop_reason"] is None


def test_hot_prospect_high_intent_is_rescued():
    # an unknown human mentioning ICP pain = a lead worth surfacing
    r = _c(_m(addr="owner@hvacguys.com", subject="we keep getting missed calls, can you help"))
    assert r["label"] == "Hot Prospect" and r["is_important"] and r["drop_reason"] is None


def test_ignore_keyword_dropped_even_without_bulk_header():
    assert _c(_m(subject="Rank #1 with our SEO service"))["drop_reason"] == "ignore/spam"
    assert _c(_m(subject="Join our free webinar"))["drop_reason"] == "ignore/spam"
    assert _c(_m(subject="We're hiring — apply now"))["drop_reason"] == "ignore/spam"


def test_tool_billing_security_kept_even_if_noreply():
    r = _c(_m(addr="no-reply@stripe.com", subject="Your invoice is ready"))
    assert r["label"] == "Tool/Billing/Security" and r["drop_reason"] is None
    # but a stripe MARKETING email (no billing word) is not rescued
    assert _c(_m(addr="news@stripe.com", subject="New features!", list_unsubscribe="<u>"))[
        "drop_reason"
    ] == "bulk/marketing"


def test_bulk_and_noreply_drop():
    assert _c(_m(addr="news@brand.com", subject="50% off", list_unsubscribe="<u>"))[
        "drop_reason"
    ] == "bulk/marketing"
    assert _c(_m(addr="no-reply@linkedin.com", subject="You appeared in 9 searches"))[
        "drop_reason"
    ] == "automated/no-reply"


def test_bulk_wins_over_high_intent():
    # marketing using lead-language is still marketing
    r = _c(_m(addr="promo@saas.com", subject="Stop missed calls!", list_unsubscribe="<u>"))
    assert r["drop_reason"] == "bulk/marketing"


def test_unknown_human_is_low_priority_kept():
    r = _c(_m(addr="someone@gmail.com", subject="quick question"))
    assert r["drop_reason"] is None and r["label"] == "Low Priority"


def test_triage_orders_important_first_and_counts_dropped():
    msgs = [
        _m(addr="news@brand.com", subject="promo", list_unsubscribe="<u>"),
        _m(addr="owner@hvac.com", subject="need automation for missed calls"),  # hot
        _m(addr="x@y.com", subject="SEO services"),  # ignore
        _m(addr="boss@roofco.com", subject="re: project"),  # client
    ]
    kept, dropped = et.triage(msgs, PRIO)
    assert kept[0]["is_important"] and kept[-1]["label"] == "Low Priority" or all(
        k["is_important"] for k in kept[:2]
    )
    labels = {m["label"] for m in kept}
    assert "Client" in labels and "Hot Prospect" in labels
    assert dropped.get("bulk/marketing") == 1 and dropped.get("ignore/spam") == 1


def test_priorities_template_is_valid():
    # The shipped priorities.json is a TEMPLATE (no personal data committed): valid JSON
    # with the expected keys. Real users supply their own domains/ICP via a gitignored
    # file pointed to by EVE_PRIORITIES.
    import priorities

    p = priorities.load()
    for key in (
        "personal_emails", "important_domains", "high_intent_keywords",
        "ignore_keywords", "billing_security_keywords", "labels",
    ):
        assert key in p, f"priorities template missing '{key}'"
    assert isinstance(p["high_intent_keywords"], list)
    assert isinstance(p["labels"], list) and p["labels"]
