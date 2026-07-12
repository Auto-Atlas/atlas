# Full voice-brain chain test for "grab my top 3 emails":
#   1. production system prompt + tool schema -> does qwen3 call check_email?
#   2. execute the REAL handler (live IMAP against Gmail)
#   3. feed the real result back -> what would Jarvis SAY out loud?
import asyncio
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
from dotenv import load_dotenv

load_dotenv(Path(__file__).parent.parent / ".env", override=True)

from persona import SYSTEM_PROMPT  # noqa: E402
from email_tool import handle_check_email  # noqa: E402

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "check_email",
            "description": (
                "Check the user's real Gmail inbox for unread email (read-only — never sends "
                "or deletes). Use for 'any new email', 'check my inbox', 'did X email me back'."
            ),
            "parameters": {
                "type": "object",
                "properties": {"limit": {"type": "number", "description": "Max messages to report, default 8."}},
                "required": [],
            },
        },
    }
]


def ask(messages):
    body = {
        "model": "qwen3:8b", "messages": messages, "tools": TOOLS,
        "stream": False, "temperature": 0.3, "max_tokens": 300, "reasoning_effort": "none",
    }
    req = urllib.request.Request(
        "http://localhost:11434/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())["choices"][0]["message"]


async def main():
    msgs = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": "grab my top three emails"},
    ]
    m = ask(msgs)
    calls = m.get("tool_calls") or []
    print(f"STEP1 tool choice: {[ (c['function']['name'], c['function']['arguments']) for c in calls ]}")
    if not calls or calls[0]["function"]["name"] != "check_email":
        print("FAIL: model did not call check_email")
        return

    args = json.loads(calls[0]["function"]["arguments"] or "{}")

    class P:
        arguments = args
        result = None

        async def result_callback(self, r, **k):
            self.result = r

    p = P()
    await handle_check_email(p)
    print(f"STEP2 real IMAP: ok={p.result['ok']} unread={p.result.get('unread_count')}")

    msgs.append({"role": "assistant", "content": "", "tool_calls": calls})
    msgs.append({"role": "tool", "tool_call_id": calls[0]["id"], "content": json.dumps(p.result)})
    final = ask(msgs)
    spoken = (final.get("content") or "").strip()
    safe = spoken.encode("ascii", "replace").decode()
    print(f"STEP3 Jarvis would say: {safe}")
    print(f"RESULT: {'PASS' if p.result['ok'] and spoken else 'FAIL'}")


asyncio.run(main())
