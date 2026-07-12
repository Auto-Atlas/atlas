# Email-reply errand (Workstream B gap 1a): check_email(from_person=…) targeted fetch
# returns message_id + from_email so gmail_send(reply_to_msg_id=…) becomes reachable.
# Headers only — the body is STILL never fetched; subjects stay fenced as untrusted.
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import email_tool


def _params(**arguments):
    return SimpleNamespace(arguments=arguments, result_callback=AsyncMock())


@pytest.fixture(autouse=True)
def creds(monkeypatch):
    monkeypatch.setattr(email_tool, "GMAIL_USER", "owner@example.com")
    monkeypatch.setattr(email_tool, "GMAIL_APP_PASSWORD", "app-pw")


def _canned(person, limit):
    assert person == "mike"
    return [
        {"from": "Mike Jones", "from_email": "mike@x.com",
         "subject": "Thursday quote", "date": "Wed, 01 Jul 2026 15:0",
         "message_id": "<abc123@mail.x.com>"},
    ][:limit]


def test_from_person_returns_threading_fields(monkeypatch):
    monkeypatch.setattr(email_tool, "_fetch_from", _canned)
    p = _params(from_person="mike")
    asyncio.run(email_tool.handle_check_email(p))
    out = p.result_callback.await_args.args[0]
    assert out["ok"] is True
    m = out["messages"][0]
    assert m["message_id"] == "<abc123@mail.x.com>"
    assert m["from_email"] == "mike@x.com"
    assert m["subject"] == "Thursday quote"
    # the instruction teaches the reply step and keeps subjects fenced
    instr = out["instruction"].lower()
    assert "reply_to_msg_id" in out["instruction"]
    assert "untrusted" in instr


def test_from_person_no_matches_is_honest(monkeypatch):
    monkeypatch.setattr(email_tool, "_fetch_from", lambda person, limit: [])
    p = _params(from_person="mike")
    asyncio.run(email_tool.handle_check_email(p))
    out = p.result_callback.await_args.args[0]
    assert out["ok"] is True and out["messages"] == []
    assert "no mail" in out["instruction"].lower()


def test_unread_path_unchanged(monkeypatch):
    # Without from_person the existing unread+triage path runs untouched.
    monkeypatch.setattr(email_tool, "_fetch_unread", lambda limit: [])
    p = _params()
    asyncio.run(email_tool.handle_check_email(p))
    out = p.result_callback.await_args.args[0]
    assert out["ok"] is True and out["unread_count"] == 0


def test_schema_advertises_from_person():
    assert "from_person" in email_tool.CHECK_EMAIL_SCHEMA.properties


def test_imap_search_atom_neutralizes_injection():
    # Model-controlled text cannot break out of the quoted atom or smuggle keys.
    assert email_tool._imap_search_atom('mike" OR SUBJECT "x') == '"mike OR SUBJECT x"'
    assert email_tool._imap_search_atom("a\\b\r\nc") == '"abc"'
    assert email_tool._imap_search_atom("x" * 300) == '"' + "x" * 100 + '"'
    with pytest.raises(ValueError):
        email_tool._imap_search_atom('"\\')
