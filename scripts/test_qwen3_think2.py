# Round 2: production has TOOLS in the request. Does the tools array trigger
# runaway thinking on the announce turn? And does Ollama's native think:false
# pass through the OpenAI-compat endpoint?
import json
import urllib.request

BASE = "http://localhost:11434/v1/chat/completions"
MODEL = "qwen3:8b"

SYS = (
    "You are Jarvis, a calm, sharp personal voice assistant speaking out loud. "
    "Reply in short, natural spoken sentences. No markdown, no bullets, no emojis. /no_think"
)
ANNOUNCE = (
    "Alex just received a text from Sam. It says: 'Hey Alex, the demo looks great!'. "
    "Announce it to him in one short natural sentence."
)
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Get real current weather and today's forecast.",
            "parameters": {"type": "object", "properties": {"place": {"type": "string"}}, "required": []},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "prepare_text",
            "description": "Stage a text message (SMS) to someone in the user's contacts. Does NOT send.",
            "parameters": {
                "type": "object",
                "properties": {"name": {"type": "string"}, "message": {"type": "string"}},
                "required": ["name", "message"],
            },
        },
    },
]

MSGS = [
    {"role": "system", "content": SYS},
    {"role": "assistant", "content": "Morning Sam, inbox is clear and it's 92 out."},
    {"role": "system", "content": ANNOUNCE},
]


def ask(label, extra=None, tools=None):
    body = {"model": MODEL, "messages": MSGS, "stream": False, "temperature": 0.3, "max_tokens": 300}
    if tools:
        body["tools"] = tools
    if extra:
        body.update(extra)
    req = urllib.request.Request(BASE, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        data = json.loads(r.read())
    m = data["choices"][0]["message"]
    usage = data.get("usage", {})
    c = (m.get("content") or "").strip()
    reasoning = (m.get("reasoning") or m.get("reasoning_content") or "").strip()
    calls = m.get("tool_calls") or []
    print(
        f"[{label}] completion_tokens={usage.get('completion_tokens')} "
        f"content_len={len(c)} reasoning_len={len(reasoning)} tool_calls={len(calls)}"
    )
    print(f"    content: {c[:140]!r}")


ask("E tools")  # no tools — control repeat of A
ask("F with tools", tools=TOOLS)
ask("G tools + think:false", extra={"think": False}, tools=TOOLS)
ask("H tools + reasoning_effort none", extra={"reasoning_effort": "none"}, tools=TOOLS)
