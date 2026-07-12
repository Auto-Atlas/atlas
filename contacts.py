#
# Contacts — name -> phone number resolution from the owner's real Google Contacts
# export (contacts.google.com -> Export -> Google CSV), dropped into the
# jarvis-inbox so re-exporting refreshes it with no code changes. Used by the
# texting tools: "text Mike I'm running late" needs "Mike" to become +1...
#
# Matching is deliberately conservative: exact > nickname > first-name >
# prefix > substring > fuzzy. Ambiguity is returned as a candidate list, not
# guessed — Jarvis asks "which Mike?" instead of texting the wrong one.
#

import csv
import os
import re
from difflib import SequenceMatcher
from pathlib import Path

from loguru import logger

CONTACTS_CSV = Path(
    os.getenv("JARVIS_CONTACTS_CSV", str(Path.home() / "jarvis-inbox" / "contacts.csv"))
)

_cache: dict = {"mtime": None, "contacts": []}


def _digits(phone: str) -> str:
    return re.sub(r"[^\d+]", "", phone or "")


def _load() -> list[dict]:
    """Load and cache contacts; reload automatically when the CSV changes."""
    if not CONTACTS_CSV.is_file():
        return []
    mtime = CONTACTS_CSV.stat().st_mtime
    if _cache["mtime"] == mtime:
        return _cache["contacts"]

    contacts = []
    with open(CONTACTS_CSV, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            first = (row.get("First Name") or "").strip()
            last = (row.get("Last Name") or "").strip()
            nick = (row.get("Nickname") or "").strip()
            org = (row.get("Organization Name") or "").strip()
            full = " ".join(p for p in (first, last) if p) or org or nick
            # Prefer the mobile number when labeled; else first non-empty.
            phones = []
            for i in (1, 2, 3):
                label = (row.get(f"Phone {i} - Label") or "").strip().lower()
                # Google CSV packs multiple numbers into ONE field separated by
                # " ::: " — split first, or _digits() concatenates them into a
                # single garbage number (20 of 425 real contacts hit this).
                for part in (row.get(f"Phone {i} - Value") or "").split(":::"):
                    value = _digits(part)
                    if value:
                        phones.append((label, value))
            if not full or not phones:
                continue
            mobile = next((v for l, v in phones if "mobile" in l), phones[0][1])
            contacts.append(
                {
                    "name": full,
                    "first": first.lower(),
                    "nick": nick.lower(),
                    "full_lower": full.lower(),
                    "phone": mobile,
                }
            )
    _cache["mtime"] = mtime
    _cache["contacts"] = contacts
    logger.info(f"Contacts loaded: {len(contacts)} entries from {CONTACTS_CSV}")
    return contacts


# The owner's own number, from .env (JARVIS_OWNER_PHONE): "text me a reminder"
# must work even though people rarely keep a contact card for themselves.
from persona import USER_NICK, self_names

OWNER_PHONE = _digits(os.getenv("JARVIS_OWNER_PHONE", ""))
_SELF_NAMES = self_names()


def resolve(query: str, max_candidates: int = 5) -> dict:
    """Resolve a spoken name to contacts. Returns {status, matches:[{name,phone}]}.

    status: "none" | "one" | "ambiguous". Exact-tier hits beat fuzzy ones; a
    single best-tier hit wins even if weaker tiers also matched.
    """
    q = (query or "").strip().lower()
    if not q:
        return {"status": "none", "matches": []}
    if q in _SELF_NAMES and OWNER_PHONE:
        return {"status": "one", "matches": [{"name": f"{USER_NICK} (you)", "phone": OWNER_PHONE}]}
    contacts = _load()
    if not contacts:
        return {
            "status": "none",
            "matches": [],
            "error": f"no contacts file at {CONTACTS_CSV} — export Google CSV and drop it in the inbox",
        }

    tiers: list[list[dict]] = [[], [], [], [], []]
    for c in contacts:
        if q == c["full_lower"] or q == c["nick"]:
            tiers[0].append(c)
        elif q == c["first"]:
            tiers[1].append(c)
        elif c["full_lower"].startswith(q) or c["first"].startswith(q):
            tiers[2].append(c)
        elif q in c["full_lower"]:
            tiers[3].append(c)
        elif SequenceMatcher(None, q, c["full_lower"]).ratio() > 0.75 or (
            c["first"] and SequenceMatcher(None, q, c["first"]).ratio() > 0.8
        ):
            tiers[4].append(c)

    best = next((t for t in tiers if t), [])
    matches = [{"name": c["name"], "phone": c["phone"]} for c in best[:max_candidates]]
    status = "none" if not matches else ("one" if len(matches) == 1 else "ambiguous")
    return {"status": status, "matches": matches}
