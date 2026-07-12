# Functional pass over the new tool handlers — every check is a REAL call:
# memory writes to the actual wiki page, news hits the live feed, notes scan
# the real inbox, reminders persist to disk, media taps a real volume key.
import asyncio
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from dotenv import load_dotenv

load_dotenv(Path(__file__).parent.parent / ".env", override=True)

results = {}


class P:
    def __init__(self, **args):
        self.arguments = args
        self.result = None

    async def result_callback(self, result, **kwargs):
        self.result = result


async def main():
    from calendar_tool import handle_get_calendar
    from email_tool import handle_check_email
    from media_tool import handle_media_control
    from memory_tool import MEMORY_PAGE, handle_recall, handle_remember, memory_pack
    from news_tool import handle_get_news
    from notes_tool import handle_search_notes
    from reminders_tool import ReminderService

    # memory: remember -> on disk -> recall -> boot pack
    p = P(fact="Owner 2026 goal: 10x the business with Atlas as the engine")
    await handle_remember(p)
    results["remember"] = p.result["ok"] and MEMORY_PAGE.is_file()
    p = P(query="2026 goal")
    await handle_recall(p)
    results["recall"] = p.result["ok"] and any("10x" in m for m in p.result["matches"])
    results["boot_pack"] = "10x" in memory_pack()

    # news: real fetch
    p = P(topic="artificial intelligence")
    await handle_get_news(p)
    results["news"] = p.result["ok"] and len(p.result["headlines"]) >= 3
    if p.result["ok"]:
        results["news_sample"] = p.result["headlines"][0]["headline"][:70]

    # notes: real inbox scan (the verification notes from earlier today exist)
    p = P(query="verification", days_back=2)
    await handle_search_notes(p)
    results["notes"] = p.result["ok"] and p.result["count"] >= 1

    # reminders: set (2 min out), list, cancel — leaves no residue
    announced = []

    async def fake_announce(text):
        announced.append(text)

    svc = ReminderService(fake_announce)
    p = P(what="test reminder - safe to ignore", minutes_from_now=2)
    await svc.handle_set(p)
    results["reminder_set"] = p.result["ok"]
    p = P()
    await svc.handle_list(p)
    results["reminder_list"] = p.result["count"] >= 1
    p = P(number=1)
    await svc.handle_cancel(p)
    results["reminder_cancel"] = p.result["ok"]

    # media: one real volume-up tap (audible-harmless), then back down
    p = P(action="volume_up")
    await handle_media_control(p)
    ok1 = p.result["ok"]
    p = P(action="volume_down")
    await handle_media_control(p)
    results["media"] = ok1 and p.result["ok"]

    # calendar + email: gated honest errors until the owner adds creds
    p = P()
    await handle_get_calendar(p)
    results["calendar_gated"] = (not p.result["ok"]) and "not connected" in p.result["error"]
    p = P()
    await handle_check_email(p)
    results["email_gated"] = (not p.result["ok"]) and "not connected" in p.result["error"]

    for k, v in results.items():
        print(f"{k}: {v}")


asyncio.run(main())

# --- tool choice spot check on qwen3 ---
TOOLS = [
    ("set_reminder", "Set a real reminder or timer spoken aloud when due.", {"what": {"type": "string"}, "minutes_from_now": {"type": "number"}}),
    ("remember", "Save a fact to permanent memory (survives restarts).", {"fact": {"type": "string"}}),
    ("get_news", "Real current news headlines, optionally by topic.", {"topic": {"type": "string"}}),
    ("media_control", "Play/pause/skip/volume for whatever is playing.", {"action": {"type": "string"}}),
    ("start_challenger_mode", "Become a hard-question strategic coach (10x thinking, pushback).", {}),
    ("get_calendar", "Read the user's real calendar for the next days.", {"days": {"type": "number"}}),
]
OAI = [
    {"type": "function", "function": {"name": n, "description": d, "parameters": {"type": "object", "properties": p, "required": []}}}
    for n, d, p in TOOLS
]
for q, want in [
    ("remind me in 20 minutes to call Alex back", "set_reminder"),
    ("remember that my anniversary is October 12th", "remember"),
    ("what's in the news today", "get_news"),
    ("skip this song", "media_control"),
    ("challenge me on my business goals, push me hard", "start_challenger_mode"),
    ("what do I have tomorrow", "get_calendar"),
]:
    body = {
        "model": "qwen3:8b",
        "messages": [{"role": "system", "content": "You are Jarvis, a voice assistant with real tools. Use them."}, {"role": "user", "content": q}],
        "tools": OAI, "stream": False, "temperature": 0.3, "max_tokens": 300, "reasoning_effort": "none",
    }
    req = urllib.request.Request("http://localhost:11434/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        m = json.loads(r.read())["choices"][0]["message"]
    calls = [tc["function"]["name"] for tc in (m.get("tool_calls") or [])]
    mark = "OK " if want in calls else "MISS"
    print(f"{mark} {q!r} -> {calls}")
