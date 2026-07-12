# Final qwen3 verification with the PRODUCTION schema descriptions:
#   A. weather question -> exactly one get_weather call (home default honored)
#   B. "check inbox and weather" -> two calls, one each, no duplicates
import json
import sys
import urllib.request

BASE = "http://localhost:11434/v1/chat/completions"
MODEL = sys.argv[1] if len(sys.argv) > 1 else "qwen3:8b"

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": (
                "Get real current weather and today's forecast. With no arguments it uses the "
                "user's home location; pass a place name for anywhere else. Use whenever the "
                "user asks about weather, what to wear, or whether to work outside."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "place": {
                        "type": "string",
                        "description": "Optional city or town, e.g. 'Boston' or 'Worcester MA'. Empty for home.",
                    }
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "check_inbox",
            "description": (
                "Read the capture inbox folder synced from the user's phone — quick notes, "
                "reminders, voice memos. Use when asked what's in the inbox or whether anything new came in."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "include_read": {"type": "boolean", "description": "Also list already-read items."}
                },
                "required": [],
            },
        },
    },
]

SYSTEM = (
    "You are Jarvis, a calm, sharp personal voice assistant speaking out loud. "
    "Reply in short, natural spoken sentences. No markdown, no bullets, no emojis. "
    "Never read out JSON or code. Use your tools for any weather or inbox question. /no_think"
)


def ask(user):
    body = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user},
        ],
        "stream": False,
        "temperature": 0.3,
        "max_tokens": 300,
        "tools": TOOLS,
    }
    req = urllib.request.Request(
        BASE, json.dumps(body).encode(), {"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["choices"][0]["message"]


for label, q in [
    ("A weather", "what's the weather like today?"),
    ("B both", "check my inbox and the weather"),
    ("C repeat-1", "what's the weather like today?"),
    ("D repeat-2", "what's the weather like today?"),
]:
    m = ask(q)
    calls = m.get("tool_calls") or []
    names = [tc["function"]["name"] for tc in calls]
    print(f"[{label}] n_calls={len(calls)} names={names} content={(m.get('content') or '')[:120]!r}")
    for tc in calls:
        print(f"    -> {tc['function']['name']}({tc['function']['arguments']})")
