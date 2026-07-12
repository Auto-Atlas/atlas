"""The owner's priorities loader — the single source of their signal-vs-noise rules.

Read by email_triage (filtering/labels) and rituals.build_strategy_task (ICP
grounding). Missing/corrupt -> {} so callers degrade to header-only triage and a
generic strategy rather than crashing.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from loguru import logger


def _path() -> Path:
    # Personal file (gitignored) first; the tracked template only as fallback.
    local = Path(__file__).parent / "priorities.local.json"
    tmpl = Path(__file__).parent / "priorities.json"
    return Path(os.getenv("EVE_PRIORITIES", str(local if local.is_file() else tmpl)))


def load() -> dict:
    p = _path()
    try:
        if p.is_file():
            return json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning(f"priorities: could not read {p} ({e}); using empty rules")
    return {}
