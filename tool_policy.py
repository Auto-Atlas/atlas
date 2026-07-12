# tool_policy.py
#
# Code-enforced preflight for tool handlers. Sits between toolguard.dedupe()
# and the real handler (dedupe stays outermost). Enforces, IN CODE:
#   - requires_fields : ask again if a required arg is missing
#   - needs_confirmation : prepare a draft instead of running; the SAME tool,
#     re-called with confirmed=true, releases the frozen draft.
# and attaches a tool's skill .md guidance to its first result (Skill Loader).
#
# Confirmation is a SINGLE-TOOL flow: a needs_confirmation tool called without
# confirmed returns a draft preview to read back; called again with
# confirmed=true (same context) it fires the FROZEN prepared args. There is no
# separate confirm_action tool — discovering and calling a second different tool
# proved brittle (the model narrated the concept and never made the call).
#
# Module-level state (_staged, _injected) is per process, but keyed PER CONTEXT
# (id(params.context)). bot.py and phone_bot.py both register these tools, so a
# single process can drive multiple sequential contexts (e.g. phone sessions);
# keying both dicts by context means each context only ever sees its own injected
# skill bodies and only ever touches its own prepared call (Codex) — no
# cross-conversation leak.
#
import copy
import dataclasses
import os
import time
from dataclasses import dataclass

from loguru import logger
from pipecat.services.llm_service import FunctionCallParams

import persona
import speaker_state

# ---- Trust-tier gating (Voice Recognition) ----------------------------------
# Trust is an axis ORTHOGONAL to risk_level: risk encodes "needs confirmation",
# this encodes "needs identity". Fail-closed in every branch.
TIER_MAX_RISK = {"owner": "high", "known": "medium", "kid": "low", "unknown": None}
_TIER_ORDER = {"unknown": 0, "kid": 1, "known": 2, "owner": 3}
_RISK_ORDER = {"low": 1, "medium": 2, "high": 3}
# Need the owner regardless of their (lower) risk_level:
OWNER_ONLY = {"jarvis_agent", "open_on_pc", "delegate_hermes", "adjust_surfacing",
              "embodiment", "complete_reminder", "look", "look_via_phone",
              "surface_visual", "set_silence_mode"}
# Owner's private data sources a child must not read aloud:
KID_DENY = {"check_email", "check_inbox", "get_calendar", "search_notes",
            "review_conversations"}


def tier_allows(tool_name: str, risk_level: str, tier: str) -> bool:
    if tier == "owner":
        return True
    if tool_name in OWNER_ONLY:
        return False
    if tier == "kid" and tool_name in KID_DENY:
        return False
    cap = TIER_MAX_RISK.get(tier)            # unknown/garbage tier -> None -> deny
    if cap is None:
        return False
    return _RISK_ORDER.get(risk_level, 99) <= _RISK_ORDER[cap]   # garbage risk -> deny


# --- Spec-5 (Phase 2A) identity-aware authorization --------------------------
# Owner-private memory is promoted to an owner-gated capability even though its
# nominal risk is `medium` (Spec 5 plan-correction: `recall` is medium today and
# thus reachable by `known` — owner-gating it is the deliberate policy change).
OWNER_MEMORY = {"recall"}


def is_owner_gated(tool_name: str, risk_level: str) -> bool:
    """Capabilities that require a real owner unlock (speaker match or short
    re-auth phrase) regardless of the device's effective tier: high-risk tools,
    OWNER_ONLY tools, and owner-private memory."""
    return (
        risk_level == "high"
        or tool_name in OWNER_ONLY
        or tool_name in OWNER_MEMORY
    )


def authz_allows(
    tool_name: str, risk_level: str, effective_tier: str, owner_unlocked: bool
) -> bool:
    """Spec-5 gate: authorization is the (device, speaker) PAIR resolved into an
    effective_tier + owner_unlocked (see identity.resolve_authz). Fail-closed.

    - unknown device/tier              -> deny everything (device itself untrusted)
    - owner-gated capability           -> require owner_unlocked (never device trust alone)
    - kid + KID_DENY                   -> deny
    - otherwise                        -> risk math against the effective_tier cap
    """
    if effective_tier == "unknown":
        return False
    if is_owner_gated(tool_name, risk_level):
        return bool(owner_unlocked)
    if effective_tier == "kid" and tool_name in KID_DENY:
        return False
    cap = TIER_MAX_RISK.get(effective_tier)
    if cap is None:
        return False
    return _RISK_ORDER.get(risk_level, 99) <= _RISK_ORDER[cap]


def _identity_v2() -> bool:
    # Read at call time so an env set after import (and tests) take effect.
    return os.getenv("EVE_IDENTITY_V2") == "1"


def _effective_authz(tool_name: str, risk_level: str) -> tuple[bool, str]:
    """(allowed, effective_tier). v2 resolves the device+speaker pair (Spec 5);
    legacy uses the single current_tier(). Default (flag off) is byte-for-byte
    the legacy path — the live voice loop is unaffected until v2 is enabled."""
    if _identity_v2():
        eff, owner_unlocked = speaker_state.resolve()
        return authz_allows(tool_name, risk_level, eff, owner_unlocked), eff
    tier = speaker_state.current_tier()
    return tier_allows(tool_name, risk_level, tier), tier


def _releaser_tier() -> str:
    """Tier used to gate releasing a staged draft — consistent with the active model."""
    return speaker_state.resolve()[0] if _identity_v2() else speaker_state.current_tier()


# tools whose skill body already rode a result, per context
_injected: dict[int, set[str]] = {}
_STAGE_TTL_S = 600                   # mirrors sms_tool; a stale "yes" can't fire
# most-recent prepared draft awaiting a confirmed=true re-call, per context
_staged: dict[int, dict] = {}


def _ctx_id(params):
    return id(getattr(params, "context", None))


def _freeze_draft(args) -> dict:
    """The SINGLE source of the draft-freeze discipline (shared by the in-context confirm
    path and the remote-stage path). Returns a deepcopy of args minus the `confirmed` key,
    so what the user approves is exactly what fires and later mutation of the original args
    or the delivered response can never reach the frozen draft. Each call returns a fresh,
    independent copy."""
    return copy.deepcopy({k: v for k, v in (args or {}).items() if k != "confirmed"})


def remote_approval_enabled() -> bool:
    """Effective remote-approval flag. The shared settings row (toggled from the app, spec
    §1.9) wins so the IN-APP toggle can flip behavior in this separate voice-loop process;
    otherwise the EVE_REMOTE_APPROVAL env default (disabled) applies. Default = BLOCK, which
    preserves the owner's locked 'block + tell them' decision. Lazy import so tool_policy has
    no hard new module dependency and degrades closed if the store is unavailable."""
    try:
        import approval_store
        # Only touch the DB if it already exists — never CREATE an empty store just to read
        # a setting on the disabled hot path (a settings row can't exist without the DB).
        if approval_store.db_exists():
            val = approval_store.get_setting("remote_approval_enabled")
            if val is not None:
                return str(val).strip().lower() == "true"
    except Exception as e:  # store missing/unreadable -> fail closed
        logger.debug(f"remote_approval_enabled: store unavailable ({e!r}); env fallback")
    return os.getenv("EVE_REMOTE_APPROVAL", "disabled").strip().lower() == "enabled"


def _remote_ttl_s() -> int:
    return int(os.getenv("EVE_REMOTE_APPROVAL_TTL_S", "14400"))   # 4h, not the 600s in-room TTL


def _summarize(name: str, frozen: dict) -> str:
    """A short human notification line (never the basis for any displayed amount — the app
    computes the amount from the frozen args, spec §1.10)."""
    if name == "create_invoice":
        cust = (frozen.get("customer") or {}).get("name", "a customer")
        total = sum((li.get("quantity", 0) or 0) * (li.get("rate", 0) or 0)
                    for li in (frozen.get("line_items") or []))
        return f"{cust} — invoice, ${total:,.2f}"
    if name == "send_to_channel":
        return f"message to {frozen.get('channel', 'a channel')}"
    return f"{name} request"


async def _maybe_stage_for_remote(name, spec, params, tier) -> bool:
    """Stage a known speaker's high-risk request for the owner's remote approval instead of
    hard-denying it. Returns True iff it staged (and already delivered the result), else
    False so wrapped() falls through to the normal refusal. Fail-closed on every branch."""
    if not (remote_approval_enabled()
            and tier == "known"
            and spec.risk_level == "high"
            and spec.needs_confirmation):
        return False
    args = dict(params.arguments or {})
    # Same requires_fields validation as the normal path — never stage an incomplete draft.
    if any(not args.get(f) for f in spec.requires_fields):
        return False
    frozen = _freeze_draft(args)
    try:
        import approval_store
        approval_id = approval_store.stage(
            name, frozen,
            requester=speaker_state.current_speaker(),
            tier="known", risk="high",
            summary=_summarize(name, frozen),
            ttl_s=_remote_ttl_s(),
        )
    except Exception as e:  # if we cannot persist the draft, do NOT claim we did — refuse
        logger.warning(f"remote stage failed for {name}: {e!r}; falling through to refusal")
        return False
    # Best-effort push; never let a push failure undo a successfully staged draft.
    try:
        import approval_push
        await approval_push.notify(_summarize(name, frozen), approval_id)
    except Exception as e:
        logger.debug(f"approval push best-effort failed: {e!r}")
    await params.result_callback({
        "ok": False,
        "staged_for_approval": True,
        "approval_id": approval_id,
        "instruction": (
            f"Tell the speaker warmly that you've sent this to {persona.USER_NAME} for "
            f"approval and it might be a little while — you'll let them know once he decides. "
            f"Do NOT say it was done."
        ),
    })
    return True


@dataclass(frozen=True)
class ToolPolicy:
    needs_confirmation: bool = False
    requires_fields: tuple[str, ...] = ()
    risk_level: str = "low"          # low | medium | high


def policy(name, spec, handler, *, skill_body=None):
    """FACTORY — call inside register_tools() so the returned wrapper closes
    over context/skill_body. Returns an async (params)->None dedupe can wrap."""

    async def wrapped(params: FunctionCallParams):
        ctx = _ctx_id(params)
        allowed, tier = _effective_authz(name, spec.risk_level)
        if not allowed:
            # Remote approval (spec §1.7): instead of a flat hard-deny, a KNOWN speaker's
            # high-risk needs_confirmation request can be STAGED for the owner to approve
            # from the app — but ONLY when opted in, and only after the SAME requires_fields
            # validation that gates the normal path (never stage an incomplete draft).
            # Default stays block: if nothing is staged we fall through to today's refusal.
            if await _maybe_stage_for_remote(name, spec, params, tier):
                return
            await params.result_callback({
                "ok": False, "denied": True, "tier": tier,
                "instruction": persona.refusal_instruction(
                    name, tier, speaker_state.current_speaker()),
            })
            return
        args = dict(params.arguments or {})
        missing = [f for f in spec.requires_fields if not args.get(f)]
        if missing:
            await params.result_callback(
                {"ok": False, "error": f"{name} needs {', '.join(missing)} — ask the user."}
            )
            return
        if spec.needs_confirmation:
            confirmed = bool(args.get("confirmed"))
            if confirmed:
                entry = _staged.get(ctx)
                expired = entry is not None and (time.monotonic() - entry["at"]) > _STAGE_TTL_S
                if entry is not None and not expired:
                    # Defense-in-depth: a lower-trust speaker cannot release a draft
                    # prepared by a higher-trust one. (Today this is unreachable — the
                    # top-of-wrapped gate already denied a non-owner's high-risk re-call
                    # — but it future-proofs a medium needs_confirmation tool.)
                    if _TIER_ORDER[_releaser_tier()] < _TIER_ORDER.get(
                            entry.get("staged_tier", "owner"), 3):
                        await params.result_callback({
                            "ok": False, "denied": True,
                            "error": f"that draft was prepared by a higher-trust speaker "
                                     f"— it needs {persona.USER_NAME}'s voice to confirm.",
                        })
                        return
                    # Fire the FROZEN prepared args — approved == sent. Consume the
                    # prepared slot BEFORE awaiting so a single yes fires at most once.
                    del _staged[ctx]
                    logger.info(f"confirmed re-call releasing {name}")
                    await _safe_release(entry, params)
                    return
                # confirmed=true with nothing prepared (or expired): do NOT create.
                # Fall through to the prepare branch so a read-back always precedes
                # creation. Strip the confirmed flag so the draft is clean.
                if expired:
                    _staged.pop(ctx, None)
            # Freeze a DEEPCOPY of the args (MINUS the confirmed key) so the released
            # handler fires values independent of both the original params and the
            # delivered response (Codex: a shallow copy shares nested customer/line_items
            # refs; mutating either after preparing would change what gets sent —
            # approved != sent). _freeze_draft is the SINGLE source of this discipline,
            # shared with the remote-stage path; each call returns its OWN fresh copy, so
            # the staged slot and the echoed read-back below never share references.
            _staged[ctx] = {"tool": name, "args": _freeze_draft(args),
                            "handler": handler, "at": time.monotonic(),
                            "staged_tier": _releaser_tier()}
            resp = {
                "ok": False,
                "needs_confirmation": True,
                # ECHO a SEPARATE frozen copy of the draft, so the read-back is grounded
                # in what actually fires but mutating the response cannot reach into
                # the prepared slot: a financial document the user approves must equal
                # the one that gets created.
                "draft": _freeze_draft(args),
                "instruction": (
                    "This is a draft preview — it is NOT created yet. Read the full "
                    "draft back to the user (every detail in `draft`: amounts, line "
                    "items, customer) and ask if you should create it. Only after they "
                    "clearly say yes, call this same tool again with confirmed set to "
                    "true. What you read back is exactly what will be created."
                ),
            }
            if skill_body and name not in _injected.get(ctx, set()):
                resp["_meta"] = {
                    "skill_guidance": skill_body,
                    "note": "internal guidance — do not read aloud; use it for the read-back",
                }
                _injected.setdefault(ctx, set()).add(name)
            await params.result_callback(resp)
            return
        await _run_with_skill(name, handler, params, skill_body)

    return wrapped


async def _run_with_skill(name, handler, params, skill_body):
    """Run the handler; on its FIRST call this session, splice the tool's skill
    .md guidance into the result dict so the model sees it with the result."""
    ctx = _ctx_id(params)
    if not skill_body or name in _injected.get(ctx, set()):
        await handler(params)
        return

    async def capture(result, **kwargs):
        if isinstance(result, dict) and "_meta" not in result:
            # Nested + flagged, NOT a top-level string — keeps a small voice model
            # from reading the markdown aloud (persona.py forbids speaking JSON/code).
            result = {**result, "_meta": {
                "skill_guidance": skill_body,
                "note": "internal guidance — do not read aloud; use it to do the tool well",
            }}
            _injected.setdefault(ctx, set()).add(name)
        await params.result_callback(result, **kwargs)

    await handler(dataclasses.replace(params, result_callback=capture))


async def _safe_release(entry, params):
    """Run a released handler with the SAME safety net toolguard gives every tool
    (Codex: the confirmed re-call replays frozen args, so it must re-create the
    exception->result net itself, or a raising handler hangs the LLM with no
    result). Tags the result with released_tool for observability."""
    delivered = False

    async def capture(result, **kwargs):
        nonlocal delivered
        delivered = True
        if isinstance(result, dict) and "released_tool" not in result:
            result = {**result, "released_tool": entry["tool"]}
        await params.result_callback(result, **kwargs)

    fake = dataclasses.replace(params, arguments=entry["args"], result_callback=capture)
    try:
        await entry["handler"](fake)
    except Exception as e:
        logger.warning(f"confirmed re-call: {entry['tool']} raised after release: {e!r}")
    if not delivered:
        await params.result_callback(
            {"ok": False, "released_tool": entry["tool"],
             "error": f"{entry['tool']} failed or returned no result"}
        )
