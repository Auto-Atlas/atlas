# thinking_state.py
#
# The "thinking" toggle (EVE Thinking Toggle / Epic T). A manual on/off boolean the owner
# controls: default OFF = fast (no reasoning); ON = the voice LLM reasons. Stored in the EXISTING
# approval_store settings table (no new store) so the app (approval_api process) and the voice
# loop share one source of truth across processes.
#
# The voice loop reads this PER TURN (voice_llm.build_chat_completion_params), so the read is
# CACHED with a short TTL (mirrors agent_bridge._facts_cache) — never a SQLite connection on every
# utterance — and never CREATES the DB just to read a setting that can't exist yet (db_exists
# guard, like tool_policy.remote_approval_enabled). Default = OFF preserves today's fast behavior.
#
# Import invariant: stdlib + approval_store only (voice runtime may read it; approval_api writes
# the setting directly via approval_store, so this module stays off the approval surface).
#
import os
import time

_KEY = "thinking_enabled"
_CACHE_TTL_S = float(os.getenv("EVE_THINKING_CACHE_TTL_S", "2.0"))
_cache: tuple[float, bool] | None = None


def _read_store() -> bool:
    try:
        import approval_store
        if not approval_store.db_exists():
            return False                      # never set -> off, and don't create the DB
        val = approval_store.get_setting(_KEY)
        return str(val).strip().lower() == "true" if val is not None else False
    except Exception:
        return False                          # store unavailable -> fail to fast mode


def enabled() -> bool:
    """Cached read for the per-turn hot path. Default False (fast)."""
    global _cache
    now = time.monotonic()
    if _cache is not None and (now - _cache[0]) < _CACHE_TTL_S:
        return _cache[1]
    val = _read_store()
    _cache = (now, val)
    return val


def set_enabled(value: bool) -> None:
    """Persist the toggle (from the app via approval_api, or the set_thinking voice tool)."""
    import approval_store
    approval_store.set_setting(_KEY, "true" if value else "false")
    _invalidate()


def _invalidate() -> None:
    global _cache
    _cache = None
