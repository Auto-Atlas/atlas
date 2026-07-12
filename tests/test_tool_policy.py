# tests/test_tool_policy.py
import asyncio, pytest
from dataclasses import dataclass, field
from typing import Callable, Optional
import tool_policy
import toolguard
import speaker_state
from tool_policy import ToolPolicy, policy


def setup_function():
    # The tier gate now runs inside wrapped(); these legacy behavior tests assume
    # the owner. Set it so they exercise the same paths as before the gate existed.
    speaker_state.set_current("W", "owner", 1.0)

# MUST mirror pipecat's FunctionCallParams: a @dataclass whose result_callback is
# a real FIELD (not a method). toolguard.dedupe() does
# dataclasses.replace(params, result_callback=capture) (toolguard.py:94) — replace()
# only accepts declared fields, so a method named result_callback raises
# "unexpected keyword argument". (BMAD: Winston flagged the dataclass; Codex caught
# that result_callback must be the field.) `context` is here because staging keys
# off it; `last_kwargs` lets tests assert run_llm/properties.
@dataclass
class FakeParams:
    arguments: dict
    context: object = None
    delivered: object = None
    last_kwargs: dict = field(default_factory=dict)
    result_callback: Optional[Callable] = None
    def __post_init__(self):
        if self.result_callback is None:
            async def _capture(result, **kwargs):
                self.delivered = result
                self.last_kwargs = kwargs
            self.result_callback = _capture

async def _noop_handler(params):
    await params.result_callback({"ok": True, "ran": True})

@pytest.mark.asyncio
async def test_missing_required_field_short_circuits():
    spec = ToolPolicy(requires_fields=("customer", "line_items"))
    wrapped = policy("create_invoice", spec, _noop_handler)
    p = FakeParams({"customer": {"name": "Browns"}})   # line_items missing
    await wrapped(p)
    assert p.delivered["ok"] is False
    assert "line_items" in p.delivered["error"]

@pytest.mark.asyncio
async def test_all_fields_present_runs_handler():
    spec = ToolPolicy(requires_fields=("customer",))
    wrapped = policy("t", spec, _noop_handler)
    p = FakeParams({"customer": {"name": "X"}})
    await wrapped(p)
    assert p.delivered == {"ok": True, "ran": True}

@pytest.mark.asyncio
async def test_skill_body_attaches_once_as_nonnarrated_meta():
    tool_policy._injected.clear()
    tool_policy._staged.clear()
    spec = ToolPolicy()
    wrapped = policy("get_weather", spec, _noop_handler, skill_body="WEATHER RULES")
    p1 = FakeParams({})
    await wrapped(p1)
    # Guidance rides a NESTED _meta object, not a top-level string, so a small
    # voice model treats it as metadata — not text to read aloud. (BMAD: Amelia.)
    assert p1.delivered["_meta"]["skill_guidance"] == "WEATHER RULES"
    assert "do not read" in p1.delivered["_meta"]["note"].lower()
    assert "_skill" not in p1.delivered                # never a top-level narratable key
    p2 = FakeParams({})
    await wrapped(p2)
    assert "_meta" not in p2.delivered                 # second call does not re-attach


@pytest.mark.asyncio
async def test_confirmation_prepares_then_runs_on_confirmed_recall():
    """SINGLE-TOOL flow: first call prepares a draft (handler does NOT run); the
    SAME tool re-called with confirmed=true fires the frozen prepared args. The
    response uses natural language — no 'staged'/'confirm_action' jargon."""
    tool_policy._staged.clear()
    ran = {}
    async def risky(params):
        ran["args"] = dict(params.arguments)
        await params.result_callback({"ok": True, "did_it": True})
    spec = ToolPolicy(needs_confirmation=True)
    wrapped = policy("create_invoice", spec, risky)

    p = FakeParams({"customer": {"name": "Browns"}, "line_items": [1]})
    await wrapped(p)
    assert p.delivered["needs_confirmation"] is True
    assert "draft" in p.delivered                      # natural draft preview
    assert ran == {}                                   # handler did NOT run
    # NO leaked internal vocabulary anywhere in the model-facing response.
    blob = str(p.delivered).lower()
    assert "staged" not in blob
    assert "confirm_action" not in blob

    c = FakeParams({"customer": {"name": "Browns"}, "line_items": [1], "confirmed": True})
    await wrapped(c)
    assert ran["args"]["customer"] == {"name": "Browns"}   # ran with prepared args
    assert "confirmed" not in ran["args"]                  # confirmed flag stripped
    assert c.delivered["did_it"] is True


@pytest.mark.asyncio
async def test_confirmed_recall_fires_frozen_args_approved_equals_sent():
    """approved == sent. What the handler fires is the args FROZEN at prepare time,
    not whatever the model sent on the confirmed re-call and not a late mutation of
    the original args or the returned draft."""
    tool_policy._staged.clear()
    received = {}
    async def handler(params):
        received["args"] = dict(params.arguments)
        await params.result_callback({"ok": True})
    spec = ToolPolicy(needs_confirmation=True)
    wrapped = policy("create_invoice", spec, handler)

    original = {"customer": {"name": "Browns"}, "line_items": [{"q": 2, "rate": 50}]}
    p = FakeParams(original)
    await wrapped(p)

    # Mutate BOTH the original params nested values AND the returned draft AFTER prepare.
    original["customer"]["name"] = "MUTATED"
    original["line_items"][0]["rate"] = 9999
    p.delivered["draft"]["customer"]["name"] = "RESPONSE_MUTATED"
    p.delivered["draft"]["line_items"][0]["rate"] = 1

    # The confirmed re-call carries DIFFERENT figures — they must be ignored; the
    # frozen draft is what fires.
    c = FakeParams({"customer": {"name": "DIFFERENT"},
                    "line_items": [{"q": 1, "rate": 1}], "confirmed": True})
    await wrapped(c)
    assert received["args"]["customer"]["name"] == "Browns"
    assert received["args"]["line_items"][0]["rate"] == 50


@pytest.mark.asyncio
async def test_confirmed_with_nothing_prepared_does_not_run():
    """confirmed=true with nothing prepared must NOT create — it prepares a draft
    instead, so a read-back always precedes creation."""
    tool_policy._staged.clear()
    ran = {"n": 0}
    async def risky(params):
        ran["n"] += 1
        await params.result_callback({"ok": True, "did_it": True})
    spec = ToolPolicy(needs_confirmation=True)
    wrapped = policy("create_invoice", spec, risky)

    c = FakeParams({"customer": {"name": "X"}, "line_items": [1], "confirmed": True})
    await wrapped(c)
    assert ran["n"] == 0                                # handler did NOT run
    assert c.delivered["needs_confirmation"] is True    # prepared a draft instead
    assert "draft" in c.delivered


@pytest.mark.asyncio
async def test_new_customer_restage_keeps_approved_equals_sent():
    """New-customer path: first release returns needs_confirmation (customer not
    found); the model re-prepares the SAME invoice + the confirm_create_customer
    flag; the second confirmed re-call creates EXACTLY those re-approved args.
    Core invariant: created_args == the args echoed in the draft (approved == sent)."""
    tool_policy._staged.clear()
    state = {"n": 0}
    async def handler(params):
        state["n"] += 1
        args = dict(params.arguments)
        if state["n"] == 1:
            await params.result_callback(
                {"ok": False, "needs_confirmation": True, "candidates": ["Brown Co", "Browne LLC"]}
            )
        else:
            await params.result_callback({"ok": True, "created_args": args})

    spec = ToolPolicy(needs_confirmation=True)
    wrapped = policy("create_invoice", spec, handler)

    args_a = {"customer": {"name": "Browns"}, "line_items": [{"q": 2, "rate": 50}]}
    p1 = FakeParams(dict(args_a))
    await wrapped(p1)
    assert p1.delivered["needs_confirmation"] is True
    assert p1.delivered["draft"] == args_a             # draft echoes what will fire

    c1 = FakeParams({**args_a, "confirmed": True})
    await wrapped(c1)
    assert state["n"] == 1                              # first release ran
    assert c1.delivered["needs_confirmation"] is True   # customer not found

    # model re-prepares the SAME invoice details PLUS the create-customer flag
    args_b = {**args_a, "confirm_create_customer": True}
    p2 = FakeParams(dict(args_b))
    await wrapped(p2)
    assert p2.delivered["draft"] == args_b             # echoes the re-approved args

    c2 = FakeParams({**args_b, "confirmed": True})
    await wrapped(c2)
    assert state["n"] == 2
    # approved == sent: what the handler created equals what the user approved
    assert c2.delivered["created_args"] == p2.delivered["draft"] == args_b


@pytest.mark.asyncio
async def test_prepare_expires(monkeypatch):
    """A stale prepared draft cannot fire — a confirmed re-call past the TTL
    re-prepares instead of creating."""
    tool_policy._staged.clear()
    ran = {"n": 0}
    spec = ToolPolicy(needs_confirmation=True)
    async def risky(params):
        ran["n"] += 1
        await params.result_callback({"ok": True})
    wrapped = policy("t", spec, risky)
    await wrapped(FakeParams({"x": 1}))
    tool_policy._staged[id(None)]["at"] -= tool_policy._STAGE_TTL_S + 1   # age past TTL
    c = FakeParams({"x": 1, "confirmed": True})
    await wrapped(c)
    assert ran["n"] == 0                                # expired draft did NOT fire
    assert c.delivered["needs_confirmation"] is True    # re-prepared instead


@pytest.mark.asyncio
async def test_dedupe_of_prepare_call_does_not_double_prepare():
    tool_policy._staged.clear()
    calls = {"n": 0}
    async def risky(params):
        calls["n"] += 1
        await params.result_callback({"ok": True})
    spec = ToolPolicy(needs_confirmation=True)
    wrapped = toolguard.dedupe("create_invoice", policy("create_invoice", spec, risky))

    args = {"customer": {"name": "Browns"}, "line_items": [1]}
    # Two identical calls in one "completion" — dedupe collapses them.
    await asyncio.gather(wrapped(FakeParams(args)), wrapped(FakeParams(args)))
    assert calls["n"] == 0                       # neither ran the handler (both prepared)
    assert id(None) in tool_policy._staged       # exactly one prepared slot outstanding
    c = FakeParams({**args, "confirmed": True})
    await wrapped(c)
    assert calls["n"] == 1                        # confirmed re-call runs it exactly once


@pytest.mark.asyncio
async def test_prepared_args_are_deepcopied():
    """approved == sent, DEFENSIVELY. The prepared args and the echoed `draft`
    response must each be an independent deepcopy of params.arguments, so mutating
    EITHER the original nested values OR the response's nested values after preparing
    cannot change what the released handler receives."""
    tool_policy._staged.clear()
    received = {}
    async def handler(params):
        received["args"] = dict(params.arguments)
        await params.result_callback({"ok": True})
    spec = ToolPolicy(needs_confirmation=True)
    wrapped = policy("create_invoice", spec, handler)

    original = {"customer": {"name": "Browns"}, "line_items": [{"q": 2, "rate": 50}]}
    p = FakeParams(original)
    await wrapped(p)

    original["customer"]["name"] = "MUTATED"
    original["line_items"][0]["rate"] = 9999
    p.delivered["draft"]["customer"]["name"] = "RESPONSE_MUTATED"
    p.delivered["draft"]["line_items"][0]["rate"] = 1

    c = FakeParams({**original, "confirmed": True})
    # restore the confirmed re-call's own copy to mutated values too — irrelevant,
    # frozen draft fires
    await wrapped(c)
    assert received["args"]["customer"]["name"] == "Browns"
    assert received["args"]["line_items"][0]["rate"] == 50


@pytest.mark.asyncio
async def test_no_confirm_action_symbols_remain():
    """confirm_action mechanism is fully retired."""
    assert not hasattr(tool_policy, "handle_confirm_action")
    assert not hasattr(tool_policy, "CONFIRM_ACTION_SCHEMA")


@pytest.mark.asyncio
async def test_injected_is_per_context():
    """Skill-body injection is keyed per context: within context A the body rides
    only the FIRST result; a second call in A does NOT re-attach; but the FIRST call
    in a DISTINCT context B DOES attach (no cross-context bleed that would make B
    miss a skill body it never saw)."""
    tool_policy._injected.clear()
    tool_policy._staged.clear()
    spec = ToolPolicy()
    wrapped = policy("get_weather", spec, _noop_handler, skill_body="WEATHER RULES")

    ctx_a, ctx_b = object(), object()
    a1 = FakeParams({}, context=ctx_a)
    await wrapped(a1)
    assert a1.delivered["_meta"]["skill_guidance"] == "WEATHER RULES"   # A first: attaches

    a2 = FakeParams({}, context=ctx_a)
    await wrapped(a2)
    assert "_meta" not in a2.delivered                                  # A second: no re-attach

    b1 = FakeParams({}, context=ctx_b)
    await wrapped(b1)
    assert b1.delivered["_meta"]["skill_guidance"] == "WEATHER RULES"   # B first: attaches


@pytest.mark.asyncio
async def test_prepared_is_per_context():
    """Preparing is isolated per context: A and B prepare independently; a confirmed
    re-call from A releases A's handler and leaves B's draft intact; a confirmed
    re-call in B with no prep of its own still fires B's prep. (Codex fix #5: a
    wrong-context confirm must not clear another context's prepared slot.)"""
    tool_policy._staged.clear()
    ran = {"a": 0, "b": 0}
    async def handler_a(params):
        ran["a"] += 1
        await params.result_callback({"ok": True, "who": "a"})
    async def handler_b(params):
        ran["b"] += 1
        await params.result_callback({"ok": True, "who": "b"})
    spec = ToolPolicy(needs_confirmation=True)
    wrapped_a = policy("tool_a", spec, handler_a)
    wrapped_b = policy("tool_b", spec, handler_b)

    ctx_a, ctx_b = object(), object()
    await wrapped_a(FakeParams({"x": 1}, context=ctx_a))
    await wrapped_b(FakeParams({"y": 2}, context=ctx_b))

    # Confirmed re-call from A: releases A only; B's draft untouched.
    ca = FakeParams({"x": 1, "confirmed": True}, context=ctx_a)
    await wrapped_a(ca)
    assert ran == {"a": 1, "b": 0}
    assert ca.delivered["who"] == "a"
    assert id(ctx_b) in tool_policy._staged          # B still prepared

    # Second confirmed re-call from A: nothing left in A's slot -> re-prepares, no fire.
    ca2 = FakeParams({"x": 1, "confirmed": True}, context=ctx_a)
    await wrapped_a(ca2)
    assert ran == {"a": 1, "b": 0}
    assert ca2.delivered["needs_confirmation"] is True

    # B is still confirmable.
    cb = FakeParams({"y": 2, "confirmed": True}, context=ctx_b)
    await wrapped_b(cb)
    assert ran == {"a": 1, "b": 1}
    assert cb.delivered["who"] == "b"
