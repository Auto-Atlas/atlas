#
# Sales role-play coach — turns Jarvis into a sparring partner grounded in
# the owner's REAL business (offers, ICP, objection bank) from their
#
# Three voice tools, registered in bot.py:
#   start_sales_roleplay(persona)  -> Jarvis becomes the PROSPECT and stays in character
#   end_sales_roleplay()           -> Jarvis becomes the COACH and grades the mock call
#   back_to_jarvis()               -> restore the normal assistant persona
#
# The swap works by replacing the system message in the live LLMContext while
# keeping the conversation history, so the coach can grade the actual transcript
# of the mock call — not a summary, the real turns.
#
# Honesty guardrails: the context pack explicitly forbids invented clients,
# testimonials, or services not in the wiki. If it's not in the pack, the coach
# can't claim it.
#

import os
from pathlib import Path

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema

from persona import ASSISTANT_NAME, USER_NAME, USER_NICK

# The owner's business pack is PERSONAL data (offers, prices, guarantees) -- it
# lives outside version control (gitignored) and the path is configurable.
_PACK_PATH = Path(os.getenv("EVE_BUSINESS_CONTEXT",
                            str(Path(__file__).parent / "business_context.md")))


def load_business_context() -> str:
    """The owner-written business pack. Missing pack = loud failure, not a silent
    coach that invents the business."""
    if not _PACK_PATH.exists():
        raise FileNotFoundError(
            f"business context pack not found at {_PACK_PATH} — the sales coach "
            "refuses to run ungrounded. Write your real offers, prices and "
            "objections to that file (markdown), or point EVE_BUSINESS_CONTEXT "
            "at where it lives."
        )
    return _PACK_PATH.read_text(encoding="utf-8")

def try_load_business_context() -> str | None:
    """The pack if the owner has written one, else None. A fresh install has no
    business pack yet — that must not stop the assistant booting — but the coach
    itself still never runs ungrounded: with None, the coach tool handlers refuse
    with instructions instead of inventing a business (see jarvis_core)."""
    if not _PACK_PATH.exists():
        return None
    return _PACK_PATH.read_text(encoding="utf-8")


DEFAULT_PERSONA = (
    "a skeptical, busy residential roofing contractor who has been burned by a "
    "marketing agency before and thinks AI is hype"
)


def roleplay_prompt(persona: str, context_pack: str) -> str:
    persona = (persona or "").strip() or DEFAULT_PERSONA
    return (
        f"You are running a SALES ROLE-PLAY out loud to help {USER_NAME} (the user) practice. "
        f"You play the PROSPECT: {persona}. {USER_NAME} plays himself — selling for the "
        "business described in the pack below.\n\n"
        "Rules of the role-play:\n"
        "- STAY IN CHARACTER as the prospect every turn. Talk like a real contractor "
        "on the phone: short sentences, plain words, a little impatient. No markdown, no emojis.\n"
        "- Be realistically difficult: raise the objections this kind of prospect actually "
        "has (from the business pack below — junk Facebook leads, no time, burned $5k on "
        "the last guy, AI is hype, crew can't handle volume). Don't roll over; only warm up "
        f"if {USER_NAME} genuinely uncovers pain, quantifies it, and earns it.\n"
        f"- Make {USER_NAME} work for discovery: volunteer nothing unless he asks a good question. "
        "If he pitches features before pain, get colder or try to get off the phone.\n"
        f"- Never break character to give advice. The ONLY exceptions: if {USER_NAME} says "
        "'end roleplay', 'end role play', or asks for coaching, call the end_sales_roleplay "
        "tool instead of replying in character.\n"
        "- Keep each reply to one to three spoken sentences, like a real call.\n\n"
        f"=== BUSINESS PACK (ground truth — the prospect's world and {USER_NAME}'s real offers) ===\n"
        f"{context_pack}\n"
        "=== END PACK ==="
    )


def coach_prompt(context_pack: str) -> str:
    return (
        f"You are {ASSISTANT_NAME} acting as {USER_NAME}'s SALES COACH, speaking out loud. The conversation "
        f"above contains the mock sales call {USER_NAME} just ran ({USER_NAME} = user; you played the "
        "prospect). Grade it against the playbook in the business pack below. Speak in "
        f"short, natural sentences — no markdown, no bullets, no emojis. Address {USER_NAME} as "
        f"'{USER_NICK}' occasionally, like a trusted cornerman.\n\n"
        "Cover, concisely and concretely, quoting his actual lines where useful:\n"
        "1. What he did well (be specific, max two things).\n"
        "2. Discovery quality: did he ask pain-funnel questions and quantify the pain in "
        "dollars (implication math), or did he pitch too early?\n"
        "3. Missed pain: what the prospect hinted at that he never dug into.\n"
        "4. Objection handling: did he use LAER (listen, acknowledge, explore, respond) "
        "or did he argue?\n"
        f"5. One sharper way to present {USER_NAME}'s actual offer for THIS prospect — using "
        "only real offers, prices, and guarantees from the pack.\n\n"
        "HONESTY RULES: ground every capability claim in the pack. There are no client "
        "case studies or testimonials yet — if proof was needed, coach him to use the "
        "prospect's own Revenue Leak number, never an invented client story. "
        f"If {USER_NAME} asks to run it again, call the start_sales_roleplay tool.\n\n"
        "=== BUSINESS PACK ===\n"
        f"{context_pack}\n"
        "=== END PACK ==="
    )


def challenger_prompt(context_pack: str, memory_pack: str) -> str:
    return (
        f"You are {ASSISTANT_NAME} in CHALLENGER MODE — {USER_NAME}'s strategic sparring partner, speaking "
        "out loud. Your one job: make his thinking 10x bigger and sharper. Short spoken "
        f"sentences, no markdown, no emojis. Address him as '{USER_NICK}'.\n\n"
        "How you operate:\n"
        "- Ask ONE hard question at a time, then stop and let him answer. Never stack "
        "three questions in a turn.\n"
        "- Push back on small thinking. If he proposes X, ask what 10x X looks like and "
        "what would have to be true to get there.\n"
        "- Attack vagueness: force numbers, deadlines, and a single next action. 'More "
        "clients' is not a goal; 'five signed contracts by August 1' is.\n"
        "- Name the uncomfortable thing: the task he's avoiding, the price he's afraid "
        "to charge, the hire or fire he's putting off. Be direct, never cruel.\n"
        "- When he commits to something, offer to remember it (the remember tool) so you "
        "can hold him to it next session — and DO bring up past commitments from memory.\n"
        "- Ground everything in his real business below. Never invent clients, revenue, "
        "or capabilities. If you need real-world data, delegate to jarvis_agent.\n"
        "- When he says he's done, wants normal Jarvis back, or the session winds down, "
        "call the back_to_jarvis tool.\n\n"
        "=== BUSINESS (ground truth) ===\n"
        f"{context_pack}\n"
        "=== MEMORY (his stated goals and commitments) ===\n"
        f"{memory_pack or 'No saved goals yet — a good first question is what he is actually building toward.'}\n"
        "=== END ==="
    )


CHALLENGER_SCHEMA = FunctionSchema(
    name="start_challenger_mode",
    description=(
        "Switch to challenger/coach mode: hard questions, pushback on small thinking, "
        "10x goal pressure, one question at a time. Use when the user asks to be "
        "challenged, coached, pushed, wants to think bigger, or says things like "
        "'challenge me', 'push me', 'help me 10x this', 'be hard on me'."
    ),
    properties={},
    required=[],
)


START_ROLEPLAY_SCHEMA = FunctionSchema(
    name="start_sales_roleplay",
    description=(
        "Start a sales practice role-play where you play a prospect (e.g. a skeptical "
        "local contractor) so the user can practice selling. Use when the user asks "
        "to role-play, practice a sales call, run a mock call, or spar."
    ),
    properties={
        "persona": {
            "type": "string",
            "description": (
                "Who to play, in the user's words, e.g. 'a skeptical local GC who "
                "thinks AI is hype'. Empty string for the default skeptical roofer."
            ),
        }
    },
    required=["persona"],
)

END_ROLEPLAY_SCHEMA = FunctionSchema(
    name="end_sales_roleplay",
    description=(
        "End the current sales role-play and switch to coaching: grade the user's "
        "questions, point out missed pain, and suggest a sharper pitch. Use when the "
        "user says 'end roleplay' or asks how they did."
    ),
    properties={},
    required=[],
)

BACK_TO_JARVIS_SCHEMA = FunctionSchema(
    name="back_to_jarvis",
    description=(
        "Leave sales practice mode entirely and return to being the normal Jarvis "
        "assistant. Use when the user says they're done practicing."
    ),
    properties={},
    required=[],
)


def swap_system_prompt(context, new_system: str) -> None:
    """Replace the PERSONA — the first system message — in the live LLMContext.
    Everything else stays: the boot memory pack rides as a second system
    message (stripping all system messages here permanently erased it), and
    the conversation history lets the coach grade the real transcript."""
    messages = list(context.get_messages())
    for i, m in enumerate(messages):
        if isinstance(m, dict) and m.get("role") == "system":
            messages[i] = {"role": "system", "content": new_system}
            break
    else:
        messages.insert(0, {"role": "system", "content": new_system})
    context.set_messages(messages)
    logger.info(f"System prompt swapped ({len(messages) - 1} other messages kept)")
