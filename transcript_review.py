#
# Transcript review — lets Jarvis answer "how did yesterday's conversations go?"
# by actually reading the JSONL conversation logs that bridge.py appends to
# transcripts/YYYY-MM-DD.jsonl. The tool returns a compact, factual digest
# (turn counts, every tool call with its outcome, failures and timeouts
# verbatim, TTFB latency, and a sample of what was asked) so the voice LLM
# can speak an honest summary instead of inventing one.
#

import asyncio
import json
import os
from datetime import date, datetime, timedelta
from pathlib import Path

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

TRANSCRIPT_DIR = Path(os.getenv("JARVIS_LOG_DIR", Path(__file__).parent / "transcripts"))

# Hard caps so the digest stays small enough for the 8B local model to use.
_MAX_FAILURES = 8
_MAX_SAMPLE_UTTERANCES = 10
_DETAIL_CHARS = 160

REVIEW_CONVERSATIONS_SCHEMA = FunctionSchema(
    name="review_conversations",
    description=(
        "Read the saved conversation log for a given day and return a factual digest: "
        "how many exchanges happened, which tools ran, every failure or timeout with its "
        "reason, response latency, and a sample of what was asked. Use when the user asks "
        "how past conversations went, what they talked about, or whether anything failed."
    ),
    properties={
        "day": {
            "type": "string",
            "description": (
                "Which day to review: 'today', 'yesterday', or a date like '2026-06-09'. "
                "Defaults to yesterday."
            ),
        }
    },
    required=[],
)


def _resolve_day(raw: str) -> date | None:
    s = (raw or "yesterday").strip().lower()
    if s in ("", "yesterday"):
        return date.today() - timedelta(days=1)
    if s == "today":
        return date.today()
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except ValueError:
        return None


def _available_days() -> list[str]:
    if not TRANSCRIPT_DIR.is_dir():
        return []
    return sorted(p.stem for p in TRANSCRIPT_DIR.glob("*.jsonl"))


def _clip(text: str, limit: int = _DETAIL_CHARS) -> str:
    text = (text or "").strip()
    return text[:limit] + ("…" if len(text) > limit else "")


def review_day(day: date) -> dict:
    """Parse one day's JSONL into a compact digest. Pure function — easy to test."""
    path = TRANSCRIPT_DIR / f"{day:%Y-%m-%d}.jsonl"
    if not path.is_file():
        return {
            "ok": False,
            "error": f"no transcript exists for {day:%Y-%m-%d}",
            "available_days": _available_days()[-7:],
        }

    user_turns = 0
    bot_lines = 0
    first_ts = last_ts = None
    tool_runs: dict[str, dict] = {}  # tool -> {"calls": n, "failed": n}
    failures: list[dict] = []
    ttfbs: list[float] = []
    sample_utterances: list[str] = []
    malformed = 0

    with open(path, encoding="utf-8") as f:
        for raw in f:
            raw = raw.strip()
            if not raw:
                continue
            try:
                ev = json.loads(raw)
            except json.JSONDecodeError:
                malformed += 1
                continue

            ts = ev.get("ts")
            if ts:
                first_ts = first_ts or ts
                last_ts = ts

            etype = ev.get("type")
            if etype == "user_transcript":
                user_turns += 1
                if len(sample_utterances) < _MAX_SAMPLE_UTTERANCES:
                    sample_utterances.append(_clip(ev.get("text", ""), 100))
            elif etype == "bot_transcript":
                bot_lines += 1
            elif etype == "tool_call":
                tool = ev.get("tool", "?")
                tool_runs.setdefault(tool, {"calls": 0, "failed": 0})["calls"] += 1
            elif etype == "tool_result":
                tool = ev.get("tool", "?")
                stats = tool_runs.setdefault(tool, {"calls": 0, "failed": 0})
                if not ev.get("ok", False):
                    stats["failed"] += 1
                    detail = _clip(str(ev.get("detail", "")))
                    if len(failures) < _MAX_FAILURES:
                        failures.append(
                            {
                                "time": (ts or "")[11:19],
                                "tool": tool,
                                "timeout": "timed out" in detail.lower()
                                or "timeout" in detail.lower(),
                                "detail": detail,
                            }
                        )
            elif etype == "metric" and ev.get("name") == "TTFBMetricsData":
                # Only the LLM's TTFB is the felt "thinking time"; STT/TTS
                # processors emit their own TTFB rows that would skew the average.
                v = ev.get("value")
                if (
                    isinstance(v, (int, float))
                    and v > 0
                    and "llm" in str(ev.get("processor", "")).lower()
                ):
                    ttfbs.append(float(v))

    total_failed = sum(t["failed"] for t in tool_runs.values())
    digest = {
        "ok": True,
        "date": f"{day:%Y-%m-%d}",
        "first_activity": (first_ts or "")[11:19],
        "last_activity": (last_ts or "")[11:19],
        "exchanges": user_turns,
        "bot_sentences": bot_lines,
        "tools": {name: stats for name, stats in sorted(tool_runs.items())},
        "tool_failures": total_failed,
        "failures": failures,
        "sample_user_requests": sample_utterances,
    }
    if ttfbs:
        digest["latency"] = {
            "avg_llm_ttfb_s": round(sum(ttfbs) / len(ttfbs), 2),
            "worst_llm_ttfb_s": round(max(ttfbs), 2),
            "turns_measured": len(ttfbs),
        }
    if malformed:
        digest["malformed_lines_skipped"] = malformed
    return digest


async def handle_review_conversations(params: FunctionCallParams):
    raw_day = str(params.arguments.get("day", "") or "")
    day = _resolve_day(raw_day)
    if day is None:
        await params.result_callback(
            {
                "ok": False,
                "error": f"could not understand the day {raw_day!r} — use 'today', "
                "'yesterday', or YYYY-MM-DD",
                "available_days": _available_days()[-7:],
            }
        )
        return
    # A heavy day's JSONL is megabytes of line-by-line JSON parsing — keep it
    # off the voice loop.
    digest = await asyncio.to_thread(review_day, day)
    logger.info(
        f"review_conversations(day={day:%Y-%m-%d}) -> ok={digest.get('ok')} "
        f"exchanges={digest.get('exchanges')} failures={digest.get('tool_failures')}"
    )
    await params.result_callback(digest)
