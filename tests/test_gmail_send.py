# Tests for gmail_send — the gated outbound email tool (SMTP + the existing app password).
# Never touches a live inbox: smtplib is mocked. The confirm/read-back gate is tool_policy's job
# (covered elsewhere); here we assert the handler sends exactly what it's given, once, and only
# when credentials exist.
import asyncio
from types import SimpleNamespace
from unittest.mock import patch

import email_tool


def _params(args):
    got = {}

    async def cb(result, **k):
        got.update(result)

    return SimpleNamespace(arguments=args, result_callback=cb), got


def test_gmail_send_missing_creds_is_honest(monkeypatch):
    monkeypatch.delenv("GMAIL_USER", raising=False)
    monkeypatch.delenv("GMAIL_APP_PASSWORD", raising=False)
    params, got = _params({"to": "b@x.com", "subject": "s", "body": "b"})
    asyncio.run(email_tool.handle_gmail_send(params))
    assert got.get("ok") is False and "set up" in got.get("error", "").lower()


def test_gmail_send_sends_once_with_frozen_fields(monkeypatch):
    monkeypatch.setenv("GMAIL_USER", "eve@example.com")
    monkeypatch.setenv("GMAIL_APP_PASSWORD", "app pw here")
    params, got = _params({"to": "b@x.com", "subject": "Quote", "body": "Hi Marco"})
    with patch("email_tool.smtplib.SMTP_SSL") as SMTP:
        server = SMTP.return_value.__enter__.return_value
        asyncio.run(email_tool.handle_gmail_send(params))
        assert server.login.call_count == 1
        assert server.login.call_args[0] == ("eve@example.com", "apppwhere")  # spaces stripped
        assert server.sendmail.call_count == 1
        frm, to, raw = server.sendmail.call_args[0]
        assert frm == "eve@example.com" and to == ["b@x.com"]
        assert "Subject: Quote" in raw and "Hi Marco" in raw
    assert got.get("ok") is True and got.get("sent_to") == "b@x.com"


def test_gmail_send_reply_sets_threading_headers(monkeypatch):
    monkeypatch.setenv("GMAIL_USER", "eve@example.com")
    monkeypatch.setenv("GMAIL_APP_PASSWORD", "pw")
    params, got = _params({"to": "b@x.com", "subject": "Re: Quote", "body": "yes",
                           "reply_to_msg_id": "<abc@mail.gmail.com>"})
    with patch("email_tool.smtplib.SMTP_SSL") as SMTP:
        server = SMTP.return_value.__enter__.return_value
        asyncio.run(email_tool.handle_gmail_send(params))
        raw = server.sendmail.call_args[0][2]
        assert "In-Reply-To: <abc@mail.gmail.com>" in raw
        assert "References: <abc@mail.gmail.com>" in raw
    assert got.get("ok") is True
