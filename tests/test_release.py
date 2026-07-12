# Tests for release.py — headless release of the REAL tool handlers.
# Real components: real handlers, real sockets (dead port + a real local 201 server).
# No mocks of the system under test. The temp-file / in-memory handlers are real
# side-effecting stand-ins for upstream tools we don't own (AutoInvoice/OpenJarvis).
import socket

import pytest

import release


def _free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


async def test_release_runs_real_handler_once_with_frozen_args(tmp_path):
    calls = tmp_path / "calls.log"

    async def real_writer(params):
        with open(calls, "a") as f:
            f.write(params.arguments["msg"] + "\n")
        await params.result_callback({"ok": True, "wrote": params.arguments["msg"]})

    release.register_releasable("test_writer", real_writer)
    out = await release.release("test_writer", {"msg": "hello"})
    assert out["ok"] is True and out["wrote"] == "hello"
    assert calls.read_text() == "hello\n"            # real side effect, exactly once


async def test_shim_exposes_arguments_and_captures_result():
    p = release.HeadlessFunctionCallParams({"a": 1})
    assert p.arguments == {"a": 1}
    await p.result_callback({"ok": True})
    assert p.result == {"ok": True}


async def test_shim_guards_stray_attribute_access():
    p = release.HeadlessFunctionCallParams({"a": 1})
    with pytest.raises(AttributeError):
        _ = p.llm                                    # loud guard — only .arguments/.result_callback


async def test_unknown_tool_returns_honest_error():
    out = await release.release("not_a_tool", {})
    assert out["ok"] is False and "error" in out


async def test_real_invoice_handler_dead_port_propagates_honest_error(monkeypatch):
    # With invoice_tool reading env lazily, setenv now reaches the network layer.
    monkeypatch.setenv("AUTOINVOICE_SERVICE_TOKEN", "test-token")   # past the token guard
    monkeypatch.setenv("AUTOINVOICE_URL", "http://127.0.0.1:1")     # nothing listening
    out = await release.release(
        "create_invoice",
        {"customer": {"name": "X"}, "line_items": [{"description": "d", "quantity": 1, "rate": 1}]},
    )
    assert out["ok"] is False and "error" in out                   # REAL socket attempt
    assert "not configured" not in out["error"].lower()            # proves past the no-token branch


async def test_real_invoice_handler_success_against_real_local_server(monkeypatch):
    # Honest SUCCESS (spec §6): a REAL local HTTP server returns 201; the real handler POSTs it.
    from aiohttp import web

    received = {}

    async def handler(request):
        received.update(await request.json())                      # real request really arrives
        return web.json_response(
            {"invoice_number": "INV-1043", "status": "DRAFT", "company_id": "field-services",
             "customer": {"name": "The Browns"}, "total_cents": 96000,
             "line_items": [{"description": "Deep clean", "quantity": 2,
                             "rate_cents": 48000, "amount_cents": 96000}]},
            status=201,
        )

    port = _free_port()
    app = web.Application()
    app.router.add_post("/invoices/structured", handler)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", port)
    await site.start()
    try:
        monkeypatch.setenv("AUTOINVOICE_SERVICE_TOKEN", "test-token")
        monkeypatch.setenv("AUTOINVOICE_URL", f"http://127.0.0.1:{port}")
        out = await release.release(
            "create_invoice",
            {"customer": {"name": "The Browns"},
             "line_items": [{"description": "Deep clean", "quantity": 2, "rate": 480}]},
        )
        assert out["ok"] is True
        assert out["invoice_number"] == "INV-1043"
        assert out["total_dollars"] == 960.0
        assert received["customer"] == {"name": "The Browns"}      # real POST body arrived
    finally:
        await runner.cleanup()


async def test_every_registered_handler_is_shim_compatible():
    # production handlers must only touch .arguments/.result_callback (no params.llm/context)
    for name in ("create_invoice", "send_to_channel"):
        assert name in release.RELEASABLE_HANDLERS
