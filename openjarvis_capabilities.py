#
# OpenJarvis capability map — the machine-readable sibling of docs/OPENJARVIS.md.
#
# Two consumers:
#   1. persona.py        -> capability_hint()      a SHORT (<=600 char) line
#                           appended to the voice SYSTEM_PROMPT so the small
#                           model knows the platform exists and what it offers.
#   2. agent_bridge.py   -> delegation_context()   a fuller intent->CLI map
#                           injected into each delegated task so the OpenJarvis
#                           orchestrator routes to the right subsystem instead
#                           of falling back to a generic shell.
#
# DESIGN NOTES (from the BMAD party-mode review, 2026-06-13):
#   * Winston/Amelia: a hand-maintained static list WILL drift. There is no
#     single `jarvis --list-all --json` introspection command, and the
#     intent->subsystem mapping is a JUDGEMENT call that can't be auto-derived.
#     So the map is curated (_INTENT_MAP) but drift is CAUGHT automatically:
#     drift_report() diffs the commands we reference against live `jarvis --help`
#     and the test asserts they still exist. Curated semantics, automated audit.
#   * Paige: the runtime map is two columns (intent -> CLI), dropping the
#     "subsystem" label that is noise for the model. Humans get the 3-column
#     table in docs/OPENJARVIS.md §10.
#   * Amelia: capability_hint() enforces its own char cap so it can't bloat the
#     SYSTEM_PROMPT. Pure strings, no I/O at import — safe to import anywhere.
#
# When OpenJarvis changes, update _INTENT_MAP + _REFERENCED_COMMANDS here AND
# the tables in docs/OPENJARVIS.md (see that file's §12).
#

from __future__ import annotations

import subprocess

# Hard cap on what we inject into the small voice model's system prompt. The
# hint competes for tokens with the user's actual words; keep it tiny.
HINT_MAX_CHARS = 600

# Curated intent -> CLI map. This is the durable knowledge. Each row is what a
# user might want, and the OpenJarvis command/agent that serves it. Keep it to
# the high-value verbs EVE should actually route — not every one of the 42
# commands (the full reference is docs/OPENJARVIS.md).
_INTENT_MAP: list[tuple[str, str]] = [
    ("research / dig into a topic", "jarvis ask --research \"<q>\"  (or --agent deep_research)"),
    ("write or edit a file, run code", "orchestrator tools: file_write, code_interpreter, shell_exec"),
    ("schedule a recurring or future task", "jarvis scheduler create \"<prompt>\" --type cron --value \"0 6 * * *\" --agent orchestrator"),
    ("monitor something over time / run autonomously", "jarvis operators activate <id>  (agent type: monitor_operative)"),
    ("message me on Telegram/Slack/Discord/Signal/WhatsApp", "jarvis channel send <target> \"<msg>\" --channel-type telegram"),
    ("index or search my documents", "jarvis memory index <path>  /  jarvis memory search \"<q>\""),
    ("build / refresh the morning digest", "jarvis digest --fresh  (agent type: morning_digest)"),
    ("look something up in my notes/wiki", "jarvis ask --research  (hybrid BM25+dense over knowledge.db)"),
    ("run a multi-step pipeline", "jarvis workflow run <name> --input \"<text>\""),
    ("connect Gmail / Obsidian / a data source", "jarvis connect --path <path>  /  jarvis connect --sync"),
]

# Top-level `jarvis` commands referenced above. drift_report() checks these
# still exist in live `jarvis --help`. Update alongside _INTENT_MAP.
_REFERENCED_COMMANDS = frozenset(
    {"ask", "scheduler", "operators", "channel", "memory", "digest", "workflow", "connect"}
)

# One-line headline for the voice system prompt (kept well under HINT_MAX_CHARS).
_HINT = (
    "Your jarvis_agent tool runs OpenJarvis — a full agent platform that can: "
    "research the web and your notes, read/write files and run code, SCHEDULE "
    "recurring tasks, run autonomous monitors, message you on Telegram/Slack/etc, "
    "index/search documents, and run workflows. When the user wants any of that, "
    "delegate to jarvis_agent and say what you're doing."
)


def capability_hint() -> str:
    """Short line for the voice SYSTEM_PROMPT. Self-capped so it can never bloat
    the small model's context. Pure string — safe at import time."""
    hint = _HINT
    if len(hint) > HINT_MAX_CHARS:
        hint = hint[: HINT_MAX_CHARS - 1].rstrip() + "…"
    return hint


def delegation_context() -> str:
    """Fuller intent->CLI map for the OpenJarvis orchestrator (injected by the
    bridge into each delegated task). Two columns, no subsystem noise."""
    rows = "\n".join(f"- {intent}  ->  {cli}" for intent, cli in _INTENT_MAP)
    return (
        "OPENJARVIS CAPABILITIES (route the task to the right one; prefer a "
        "specific subsystem over a raw shell command):\n" + rows
    )


def _live_command_names(timeout: float = 3.0) -> set[str]:
    """Best-effort: parse top-level command names from `jarvis --help`.
    Returns an empty set on any failure (binary missing, timeout, parse error)
    so callers can degrade gracefully — never raises."""
    try:
        proc = subprocess.run(
            ["jarvis", "--help"],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError):
        return set()
    if proc.returncode != 0:
        return set()
    names: set[str] = set()
    in_commands = False
    for line in proc.stdout.splitlines():
        if line.strip() == "Commands:":
            in_commands = True
            continue
        if in_commands:
            # Command rows look like "  ask    Ask Jarvis a question."
            if line[:2] == "  " and line[2:3].isalpha():
                names.add(line.split()[0])
            elif line.strip() == "":
                continue
            else:
                # A non-blank, non-indented line ends the Commands block (e.g. a
                # future epilog). Stop so we never misread trailing text as a
                # command name. (BMAD review: Amelia, parser fragility.)
                break
    return names


def drift_report(timeout: float = 3.0) -> dict[str, list[str]]:
    """Audit the curated map against the live CLI. Used by the test and by
    `jarvis`-aware maintenance. Empty 'missing' == no drift detected.

    Returns {"missing": [...], "live_unknown": bool-as-list} where 'missing' are
    referenced commands no longer present in `jarvis --help`."""
    live = _live_command_names(timeout=timeout)
    if not live:
        # Could not introspect (e.g. jarvis not on PATH in CI) — report nothing
        # rather than a false positive.
        return {"missing": [], "checked": []}
    missing = sorted(c for c in _REFERENCED_COMMANDS if c not in live)
    return {"missing": missing, "checked": sorted(live)}


if __name__ == "__main__":
    print(capability_hint())
    print()
    print(delegation_context())
    print()
    print("drift:", drift_report())
