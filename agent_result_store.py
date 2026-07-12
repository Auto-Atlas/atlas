# agent_result_store.py
#
# Persist a delegated agent's FULL result so a long answer is never lost to truncation
# (Codex audit, TODO.md:132). The Agent Hub callback path used to hard-cut a result to
# ~1500 chars before EVE spoke it — destroying the actual work (research, a drafted doc).
#
# Instead: when a result exceeds EVE_AGENT_RESULT_INLINE_MAX, write the WHOLE text to a file
# in the agent-results dir and deliver a SUMMARY + the saved path. EVE can then say
# "…the rest is saved to <path>". Short results stay inline, byte-for-byte unchanged.
#
# Pure-ish + unit-testable: save_agent_result() does I/O against a dir from env (monkeypatchable);
# summarize_result() is pure. No wall-clock randomness — the correlation_id is already unique.
#
# Import invariant: stdlib only. NEVER pull the voice runtime / db into this seam.
#
import os
import re
from pathlib import Path

# Reuse the same workspace root the agent bridge sandboxes to (agent_bridge.py).
WORKSPACE = Path(os.getenv("JARVIS_WORKSPACE", str(Path.home() / "jarvis-workspace")))


def _results_dir() -> Path:
    """Where full results land. Read at call time so tests can monkeypatch JARVIS_WORKSPACE."""
    root = Path(os.getenv("JARVIS_WORKSPACE", str(Path.home() / "jarvis-workspace")))
    return root / "agent-results"


def inline_max() -> int:
    """Above this many chars, a result is saved to a file instead of spoken in full."""
    try:
        return int(os.getenv("EVE_AGENT_RESULT_INLINE_MAX", "1500"))
    except ValueError:
        return 1500


def _slug(s: str) -> str:
    """Filesystem-safe, readable token (agent name / correlation id)."""
    s = re.sub(r"[^A-Za-z0-9._-]+", "-", str(s or "")).strip("-")
    return s or "agent"


def save_agent_result(agent: str, cid: str, text: str) -> str:
    """Write the FULL result text to <workspace>/agent-results/<agent>-<cid>.md and return
    the absolute path. The correlation_id is already unique, so no time-based suffix is needed
    (a re-delivery of the same cid overwrites the same file — idempotent, no orphans)."""
    d = _results_dir()
    d.mkdir(parents=True, exist_ok=True)
    path = d / f"{_slug(agent)}-{_slug(cid)}.md"
    path.write_text(text, encoding="utf-8")
    return str(path)


def _summary(text: str, cap: int) -> str:
    """First whole sentence if it fits comfortably, else a hard char window — never mid-truncate
    the delivered line into a 1500-char wall of data."""
    head = text[:cap].strip()
    m = re.search(r"(?s)^(.*?[.!?])(\s|$)", head)
    if m and len(m.group(1)) >= 40:
        return m.group(1).strip()
    return head


def summarize_result(text: str, cap: int, path: str) -> str:
    """The text EVE actually delivers. Under the cap → the full text, inline (unchanged behavior).
    Over the cap → a short summary + a pointer to the saved file, so the work is recoverable and
    EVE can say where the rest lives."""
    text = text or ""
    if len(text) <= cap:
        return text
    summary = _summary(text, cap)
    return f"{summary}\n\n(Full result saved to {path})"
