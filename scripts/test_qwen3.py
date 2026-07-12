# One-off verification for the qwen3:8b voice-LLM swap (master plan Phase 2 #8).
# Asks three things through the SAME OpenAI-compat endpoint pipecat uses:
#   1. plain chat: does <think> leak into content? how long is the reply?
#   2. tool prompt: does it emit exactly ONE tool call (llama3.1 emitted 4-9)?
#   3. narrate a tool result: does it speak JSON or natural language?
import json
import sys
import urllib.request

BASE = "http://localhost:11434/v1/chat/completions"
MODEL = sys.argv[1] if len(sys.argv) > 1 else "qwen3:8b"

SYSTEM = (
    "You are Jarvis, a calm, sharp personal voice assistant speaking out loud. "
    "Reply in short, natural spoken sentences. No markdown, no bullets, no emojis. "
    "Never read out JSON or code. /no_think"
)

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Real current conditions and today's forecast for a place.",
            "parameters": {
                "type": "object",
                "properties": {"place": {"type": "string", "description": "City name"}},
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "check_inbox",
            "description": "Read the capture inbox folder synced from the phone.",
            "parameters": {
                "type": "object",
                "properties": {"include_read": {"type": "boolean"}},
                "required": [],
            },
        },
    },
]


def ask(messages, tools=None):
    body = {
        "model": MODEL,
        "messages": messages,
        "stream": False,
        "temperature": 0.3,
        "max_tokens": 300,
    }
    if tools:
        body["tools"] = tools
    req = urllib.request.Request(
        BASE, json.dumps(body).encode(), {"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["choices"][0]["message"]


# 1: plain chat
m = ask([{"role": "system", "content": SYSTEM}, {"role": "user", "content": "hey jarvis, you there?"}])
c = m.get("content") or ""
print(f"T1 plain chat: think_leak={'<think>' in c} len={len(c)}")
print(f"   content: {c[:200]!r}")

# 2: should be exactly one tool call
m = ask(
    [{"role": "system", "content": SYSTEM}, {"role": "user", "content": "what's the weather like today?"}],
    tools=TOOLS,
)
calls = m.get("tool_calls") or []
c = m.get("content") or ""
print(f"T2 tool call: n_calls={len(calls)} content_leak={'{' in c or '<think>' in c}")
for tc in calls:
    print(f"   call: {tc['function']['name']}({tc['function']['arguments']})")

# 3: narrate a result without parroting the dict
m = ask(
    [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": "what's the weather like today?"},
        {
            "role": "assistant",
            "content": "",
            "tool_calls": [
                {
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "get_weather", "arguments": '{"place": "Worcester"}'},
                }
            ],
        },
        {
            "role": "tool",
            "tool_call_id": "call_1",
            "content": json.dumps(
                {"ok": True, "place": "Worcester", "now_f": 88, "condition": "sunny", "high_f": 91, "low_f": 68}
            ),
        },
    ],
    tools=TOOLS,
)
c = m.get("content") or ""
print(f"T3 narrate: json_parrot={('ok' in c and 'True' in c) or '{' in c} think_leak={'<think>' in c}")
print(f"   content: {c[:250]!r}")
