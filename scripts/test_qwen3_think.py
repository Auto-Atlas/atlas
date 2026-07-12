# Reproduce the silent-announce bug: qwen3 burned 300 tokens thinking and
# produced no content for the SMS announce turn. Try the exact turn shape with
# different placements of the /no_think switch and message roles.
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


def ask(messages, label):
    body = {"model": MODEL, "messages": messages, "stream": False, "temperature": 0.3, "max_tokens": 300}
    req = urllib.request.Request(BASE, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        data = json.loads(r.read())
    m = data["choices"][0]["message"]
    usage = data.get("usage", {})
    c = (m.get("content") or "").strip()
    reasoning = (m.get("reasoning") or m.get("reasoning_content") or "").strip() if isinstance(m, dict) else ""
    print(f"[{label}] completion_tokens={usage.get('completion_tokens')} content_len={len(c)} reasoning_len={len(reasoning)}")
    print(f"    content: {c[:140]!r}")


# A: the failing shape — system(/no_think), assistant greeting, then the
#    announce as a SYSTEM message (what pipecat does with developer for Ollama)
ask(
    [
        {"role": "system", "content": SYS},
        {"role": "assistant", "content": "Morning Sam, inbox is clear and it's 92 out."},
        {"role": "system", "content": ANNOUNCE},
    ],
    "A dev-as-system",
)

# B: announce as system WITH its own /no_think
ask(
    [
        {"role": "system", "content": SYS},
        {"role": "assistant", "content": "Morning Sam, inbox is clear and it's 92 out."},
        {"role": "system", "content": ANNOUNCE + " /no_think"},
    ],
    "B system+/no_think",
)

# C: announce as USER role
ask(
    [
        {"role": "system", "content": SYS},
        {"role": "assistant", "content": "Morning Sam, inbox is clear and it's 92 out."},
        {"role": "user", "content": ANNOUNCE},
    ],
    "C user role",
)

# D: announce as user WITH /no_think
ask(
    [
        {"role": "system", "content": SYS},
        {"role": "assistant", "content": "Morning Sam, inbox is clear and it's 92 out."},
        {"role": "user", "content": ANNOUNCE + " /no_think"},
    ],
    "D user+/no_think",
)
