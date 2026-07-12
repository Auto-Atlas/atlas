# Tests for wiring delegate_hermes into the voice tool registry (EVE Agent Hub).
def test_delegate_hermes_schema_in_all_tool_schemas():
    import jarvis_core
    names = {s.name for s in jarvis_core.ALL_TOOL_SCHEMAS}
    assert "delegate_hermes" in names


def test_disabled_delegates_have_no_schema():
    import jarvis_core
    names = {s.name for s in jarvis_core.ALL_TOOL_SCHEMAS}
    assert "delegate_jarvis" not in names      # disabled (Phase 3)
    assert "delegate_open_claw" not in names   # BLOCKED (Phase 2)


def test_delegate_hermes_is_owner_only():
    import tool_policy
    assert "delegate_hermes" in tool_policy.OWNER_ONLY


def test_delegate_hermes_skill_is_high_risk_and_confirms():
    # The declarative gate: a missing/mismatched skill would silently downgrade the delegate.
    import jarvis_core
    sk = jarvis_core._SKILLS.get("delegate_hermes")
    assert sk is not None and sk.risk == "high" and sk.requires_confirmation is True


def test_delegate_hermes_policy_is_high_and_confirming():
    import jarvis_core
    pol = jarvis_core._policy_for("delegate_hermes")
    assert pol.risk_level == "high" and pol.needs_confirmation is True


def test_register_tools_registers_delegate_hermes():
    # Exercises the real registration loop (incl. its startup skill-risk assertions): a fake
    # llm captures registered names; delegate_hermes must appear alongside the static tools.
    import types

    import jarvis_core
    registered = []

    class FakeLLM:
        def register_function(self, name, fn):
            registered.append(name)

    context, _ = jarvis_core.build_context()

    async def _noop(params):
        pass

    reminders = types.SimpleNamespace(handle_set=_noop, handle_list=_noop, handle_cancel=_noop)
    jarvis_core.register_tools(FakeLLM(), context, "biz pack", reminders)
    assert "delegate_hermes" in registered
    assert "jarvis_agent" in registered        # static tools still register
    assert "delegate_jarvis" not in registered  # disabled spec not registered


def test_delegate_handler_emits_agent_task_assigned(monkeypatch, tmp_path):
    # The app's Approvals live feed opens a card the moment a task is handed off — the
    # handler must emit agent_task_assigned (agent, task text, stable task_id) through the
    # bridge seam, on the fallback/poller path as well as the A2A path.
    import asyncio
    import importlib
    import types

    import approval_store
    db = str(tmp_path / "approvals.db")
    monkeypatch.setenv("EVE_APPROVAL_DB", db)
    monkeypatch.delenv("EVE_A2A_ENABLED", raising=False)   # force the fallback path
    approval_store.set_db_path(db)
    import agent_tasks
    importlib.reload(agent_tasks)

    import jarvis_core
    from delegate_registry import REGISTRY

    emitted = []
    handler = jarvis_core._make_delegate_handler(REGISTRY["hermes"], emit=emitted.append)

    results = []

    async def cb(r):
        results.append(r)

    params = types.SimpleNamespace(arguments={"task": "check the deploy"}, result_callback=cb)
    asyncio.run(handler(params))

    assert results and results[0]["ok"] is True
    events = [e for e in emitted if e.get("type") == "agent_task_assigned"]
    assert events, f"no agent_task_assigned emitted; got {emitted}"
    evt = events[0]
    assert evt["agent"] == "hermes"
    assert evt["task_id"] == results[0]["correlation_id"]
    assert "check the deploy" in evt["task"]
    assert evt["status"] == "pending"


def test_delegate_handler_awaits_async_emit(monkeypatch, tmp_path):
    # bridge.broadcast is a coroutine function in prod — the handler must actually await
    # it (a swallowed NameError here once let the sync-emit test pass while prod broke).
    import asyncio
    import importlib
    import types

    import approval_store
    db = str(tmp_path / "approvals.db")
    monkeypatch.setenv("EVE_APPROVAL_DB", db)
    monkeypatch.delenv("EVE_A2A_ENABLED", raising=False)
    approval_store.set_db_path(db)
    import agent_tasks
    importlib.reload(agent_tasks)

    import jarvis_core
    from delegate_registry import REGISTRY

    emitted = []

    async def emit(evt):
        emitted.append(evt)

    handler = jarvis_core._make_delegate_handler(REGISTRY["hermes"], emit=emit)

    async def cb(r):
        pass

    params = types.SimpleNamespace(arguments={"task": "ship it"}, result_callback=cb)
    asyncio.run(handler(params))
    assert [e["type"] for e in emitted] == ["agent_task_assigned"]
