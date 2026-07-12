#
# Self-diagnosis — gives Jarvis a REAL answer to "what tools do you have /
# what are we missing?" instead of an 8B model's vibes. Everything reported
# is checked live or pulled from the actual tool registry at call time:
#
#   - installed tools come from the FunctionSchemas registered in bot.py
#     (injected at startup, so this can never drift from reality)
#   - each dependency is probed: Ollama, the agent brain chain (claude /
#     codex CLIs, the OpenJarvis server), SMS gateway config, contacts CSV,
#     inbox sync
#   - the "missing" list is an honest, maintained inventory of capabilities
#     Jarvis does NOT have yet, each with how it would be added
#
# Small models narrate handed data well; asked to introspect from nothing
# they invent. Same design lesson as the boot brief.
#

import os
import shutil
from pathlib import Path

import aiohttp
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

SYSTEM_REPORT_SCHEMA = FunctionSchema(
    name="system_report",
    description=(
        "Audit Jarvis's own REAL capabilities: every installed tool, live health "
        "checks of the brains and integrations, and the honest list of capabilities "
        "not built yet. Use whenever the user asks what tools you have, what's "
        "missing, what to add, system status, or tells you to diagnose yourself."
    ),
    properties={},
    required=[],
)

# Capabilities Jarvis does NOT have. Keep honest and current: when one ships,
# delete it here and register its schema in bot.py.
NOT_BUILT_YET = [
    {"capability": "email sending", "note": "email is read-only; composing/sending would need the Gmail API instead of IMAP"},
    {"capability": "calendar writing", "note": "calendar is read-only via the iCal feed; creating events needs the Google Calendar API"},
    {"capability": "specific-song playback", "note": "media keys control whatever is playing; picking a song by name needs the Spotify API"},
    {"capability": "chat apps", "note": "Telegram/Discord/WhatsApp messaging (Hermes) is wired to the agent tier only, no channels configured yet"},
    {"capability": "screen awareness", "note": "cannot see the PC screen or what window is open"},
    {"capability": "smart home", "note": "no lights/thermostat/device control"},
]

_TIMEOUT = aiohttp.ClientTimeout(total=4)


async def _http_ok(session: aiohttp.ClientSession, url: str) -> bool:
    try:
        async with session.get(url) as resp:
            return resp.status < 500
    except Exception:
        return False


def make_system_report_handler(registered_tools: list[FunctionSchema]):
    """Build the handler around the ACTUAL tool registry from bot.py."""

    async def handle_system_report(params: FunctionCallParams):
        tools = [
            {"name": t.name, "what": (t.description or "").split(". ")[0]}
            for t in registered_tools
        ]

        checks: dict = {}
        llm_base = os.getenv("JARVIS_LLM_BASE_URL", "http://localhost:11434/v1").rstrip("/")
        async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
            checks["voice_llm"] = {
                "ok": await _http_ok(session, f"{llm_base}/models"),
                "model": os.getenv("OLLAMA_MODEL", "qwen3:8b"),
                "endpoint": llm_base,
            }
            checks["agent_server_local"] = {
                "ok": await _http_ok(session, os.getenv("JARVIS_AGENT_URL", "http://127.0.0.1:8000") + "/v1/models"),
            }
        checks["agent_brain_claude"] = {"ok": bool(shutil.which("claude.cmd") or shutil.which("claude"))}
        checks["agent_brain_codex"] = {"ok": bool(shutil.which("codex.cmd") or shutil.which("codex"))}
        # SMS health depends on which backend is active — checking the SMS Gate
        # vars while MacroDroid is the backend always false-reports "broken".
        sms_backend = os.getenv("JARVIS_SMS_BACKEND", "macrodroid").lower()
        if sms_backend == "macrodroid":
            sms_ok = bool(os.getenv("JARVIS_MACRODROID_SEND_URL"))
            sms_note = "send path via MacroDroid webhook; receive path is the MacroDroid bridge"
        else:  # smsgate
            sms_ok = bool(os.getenv("JARVIS_SMS_GATEWAY_URL") and os.getenv("JARVIS_SMS_PASS"))
            sms_note = "send path (SMS Gate on the phone); receive path is the MacroDroid bridge"
        checks["sms_gateway"] = {"ok": sms_ok, "backend": sms_backend, "note": sms_note}
        contacts_csv = Path(os.getenv("JARVIS_CONTACTS_CSV", str(Path.home() / "jarvis-inbox" / "contacts.csv")))
        checks["contacts"] = {"ok": contacts_csv.is_file()}
        inbox = Path(os.getenv("JARVIS_INBOX_DIR", str(Path.home() / "jarvis-inbox")))
        checks["inbox_sync"] = {"ok": inbox.is_dir()}
        import google_calendar_native as gnative
        _g_connected = gnative.is_connected()
        if _g_connected:
            _cal_write = "google (native OAuth)"
        elif os.getenv("EVE_CAL_WRITE_URL"):
            _cal_write = "apps-script webhook (legacy)"
        else:
            _cal_write = "not set up"
        checks["calendar"] = {
            "ok": _g_connected or bool(os.getenv("JARVIS_CALENDAR_ICS_URL")),
            "google_connected": _g_connected,
            "write_path": _cal_write,
            "note": ("connected via Google OAuth — read+write" if _g_connected else
                     "read via secret iCal URL; connect Google Calendar for writes "
                     "(docs/google-calendar.md)"),
        }
        checks["email"] = {
            "ok": bool(os.getenv("GMAIL_USER") and os.getenv("GMAIL_APP_PASSWORD")),
            "note": "needs a Gmail app password in .env (read-only IMAP)",
        }
        memory_page = Path(os.getenv("JARVIS_MEMORY_PAGE", str(Path.home() / "jarvis-memory.md")))
        checks["memory_wiki"] = {"ok": memory_page.parent.is_dir()}

        broken = [k for k, v in checks.items() if not v.get("ok")]
        result = {
            "ok": True,
            "tools_installed": tools,
            "health_checks": checks,
            "not_built_yet": NOT_BUILT_YET,
            "instruction": (
                f"Real audit data. Summarize OUT LOUD in a few short sentences: "
                f"{len(tools)} tools installed"
                + (f", broken right now: {', '.join(broken)}" if broken else ", all health checks passing")
                + ". Then name the three missing capabilities you'd rank highest for the user "
                "and ask which to build. If they want a build plan, delegate the design to "
                "jarvis_agent. Never read the raw list verbatim."
            ),
        }
        logger.info(f"system_report -> {len(tools)} tools, broken={broken or 'none'}")
        await params.result_callback(result)

    return handle_system_report
