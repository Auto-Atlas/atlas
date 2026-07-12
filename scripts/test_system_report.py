# Verify the self-diagnosis path end to end (minus the mic):
#   1. run the real system_report handler and print its audit
#   2. ask qwen3 the owner's exact questions with the production-style tool set and
#      confirm it picks the system_report tool instead of vibing an answer
import asyncio
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from pipecat.adapters.schemas.function_schema import FunctionSchema

from diagnostics_tool import SYSTEM_REPORT_SCHEMA, make_system_report_handler

FAKE_TOOLS = [
    SYSTEM_REPORT_SCHEMA,
    FunctionSchema(name="get_weather", description="Get real current weather. Use for any weather question.", properties={}, required=[]),
    FunctionSchema(name="check_inbox", description="Read the capture inbox folder synced from the phone.", properties={}, required=[]),
    FunctionSchema(name="jarvis_agent", description="Delegate a task to a full agent with real tools: web search, files, code.", properties={"task": {"type": "string"}}, required=["task"]),
]


class FakeParams:
    arguments: dict = {}

    async def result_callback(self, result):
        broken = [k for k, v in result["health_checks"].items() if not v.get("ok")]
        print(f"T1 handler: ok={result['ok']} tools={len(result['tools_installed'])} "
              f"missing={len(result['not_built_yet'])} broken={broken or 'none'}")


asyncio.run(make_system_report_handler(FAKE_TOOLS)(FakeParams()))

# --- tool choice ---
SYS = (
    "You are Jarvis, a voice assistant. Short spoken sentences. "
    "system_report: a real audit of your own capabilities — installed tools, live health "
    "checks, and what is NOT built yet. You MUST call it for any question about your own "
    "tools, what's missing, what to add, or system status. Never answer those from memory."
)
OAI_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": t.name,
            "description": t.description,
            "parameters": {"type": "object", "properties": t.properties or {}, "required": t.required or []},
        },
    }
    for t in FAKE_TOOLS
]

for q in [
    "What tools are we missing?",
    "Connecting the tools bro, like tell me what we don't have.",
    "I want to increase our tool capability, what should we work on?",
    "diagnose yourself",
]:
    body = {
        "model": "qwen3:8b",
        "messages": [{"role": "system", "content": SYS}, {"role": "user", "content": q}],
        "tools": OAI_TOOLS,
        "stream": False,
        "temperature": 0.3,
        "max_tokens": 300,
        "reasoning_effort": "none",
    }
    req = urllib.request.Request(
        "http://localhost:11434/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        m = json.loads(r.read())["choices"][0]["message"]
    calls = [tc["function"]["name"] for tc in (m.get("tool_calls") or [])]
    print(f"T2 {q!r} -> calls={calls or 'NONE'} content={(m.get('content') or '')[:80]!r}")
