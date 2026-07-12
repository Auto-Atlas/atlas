#
# Inbox — phone-to-PC capture review. Syncthing (PC) + Syncthing-Fork (phone)
# sync a folder of quick notes/voice memos into ~\jarvis-inbox; this tool lets
# Jarvis answer "anything in my inbox?" by actually reading that folder.
#
# Text files (.md/.txt) are read and clipped; everything else (audio, photos)
# is listed by name and size so Jarvis can say it's there without pretending
# to know what's inside. Syncthing's internal folders (.stfolder,
# .stversions) are ignored.
#
# "New" tracking is a SEEN MAP of {relative path: mtime}, not a wall-clock
# watermark. Syncthing preserves source modification times, so a note written
# on the phone at 9:00 that syncs at 9:20 arrives "older" than a 9:10
# watermark — the old scheme silently lost exactly the captures this tool
# exists to catch. A file is new if its path is unknown or its mtime moved.
# Overflow beyond _MAX_ITEMS stays unseen and surfaces on the next check
# instead of being skipped past forever.
#

import asyncio
import json
import os
from datetime import datetime
from pathlib import Path

from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

INBOX_DIR = Path(os.getenv("JARVIS_INBOX_DIR", str(Path.home() / "jarvis-inbox")))
STATE_FILE = Path(__file__).parent / "inbox_state.json"

_TEXT_EXTS = {".md", ".txt", ".text"}
_MAX_ITEMS = 15
_CLIP_CHARS = 400

CHECK_INBOX_SCHEMA = FunctionSchema(
    name="check_inbox",
    description=(
        "Read the user's capture inbox — a folder of notes and files synced from their "
        "phone. Returns new items since the last check: note text is included verbatim, "
        "other files (voice memos, photos) are listed by name. Use when the user asks "
        "what's in their inbox, mentions a thought or reminder they captured on their "
        "phone, or asks if anything new came in."
    ),
    properties={
        "all": {
            "type": "boolean",
            "description": "True to list everything in the inbox, not just items new since the last check.",
        }
    },
    required=[],
)


def _scan_files() -> list[tuple[Path, str, float]]:
    """(path, relpath, mtime) for every real file; Syncthing internals and
    hidden files skipped; files that vanish mid-scan skipped, not crashed on."""
    out = []
    for path in sorted(INBOX_DIR.rglob("*")):
        try:
            if not path.is_file():
                continue
            rel = path.relative_to(INBOX_DIR)
            if any(part.startswith(".") for part in rel.parts):
                continue
            out.append((path, str(rel), path.stat().st_mtime))
        except OSError:
            continue  # deleted/locked between listing and stat
    return out


def _load_seen() -> dict[str, float] | None:
    """The seen-map, or None when the state file holds the legacy
    single-watermark format (or nothing readable)."""
    try:
        data = json.loads(STATE_FILE.read_text(encoding="utf-8"))
        seen = data.get("seen")
        if isinstance(seen, dict):
            return {str(k): float(v) for k, v in seen.items()}
    except Exception:
        pass
    return None


def _save_seen(seen: dict[str, float]) -> None:
    try:
        STATE_FILE.write_text(
            json.dumps({"seen": seen, "saved": datetime.now().isoformat()}),
            encoding="utf-8",
        )
    except Exception as e:  # state is a convenience; never fail the tool over it
        logger.warning(f"inbox state not saved: {e}")


def check_inbox(include_all: bool = False) -> dict:
    """Scan the inbox folder. Pure-ish (touches only the state file) — testable."""
    if not INBOX_DIR.is_dir():
        return {
            "ok": False,
            "error": f"inbox folder does not exist: {INBOX_DIR}",
        }

    files = _scan_files()
    seen = _load_seen()
    if seen is None:
        # Legacy watermark state (or none): seed with what's here right now so
        # the migration doesn't narrate the folder's entire history. Anything
        # arriving after this moment is caught by path+mtime regardless of
        # how old Syncthing says it is.
        seen = {rel: mtime for _, rel, mtime in files}
        _save_seen(seen)
        if not include_all:
            return {
                "ok": True,
                "inbox": str(INBOX_DIR),
                "new_items": 0,
                "total_items": len(files),
                "items": [],
                "note": (
                    "inbox is empty"
                    if not files
                    else f"nothing new since the last check, but the inbox holds "
                    f"{len(files)} item{'s' if len(files) != 1 else ''} — say "
                    f"'what's in my inbox' to hear them"
                ),
            }

    new = [
        (path, rel, mtime)
        for path, rel, mtime in files
        if include_all or seen.get(rel) is None or mtime > seen[rel]
    ]
    # Oldest first so the report never advances past unreported items; in
    # all-mode newest first since nothing can be lost there.
    new.sort(key=lambda x: x[2], reverse=include_all)
    report, overflow = new[:_MAX_ITEMS], max(0, len(new) - _MAX_ITEMS)

    items = []
    for path, rel, mtime in report:
        entry = {
            "file": rel,
            "modified": datetime.fromtimestamp(mtime).strftime("%Y-%m-%d %H:%M"),
        }
        if path.suffix.lower() in _TEXT_EXTS:
            try:
                # utf-8-sig strips the BOM Windows editors prepend to notes.
                # Capped read: one giant file must not stall the voice loop.
                with open(path, encoding="utf-8-sig", errors="replace") as f:
                    text = f.read(_CLIP_CHARS * 2).strip()
                entry["text"] = text[:_CLIP_CHARS] + ("…" if len(text) > _CLIP_CHARS else "")
            except OSError as e:
                entry["text"] = f"(unreadable: {e})"
        else:
            entry["kind"] = path.suffix.lstrip(".").lower() or "file"
            try:
                entry["size_kb"] = round(path.stat().st_size / 1024, 1)
            except OSError:
                pass
        items.append(entry)
        seen[rel] = mtime

    # Prune entries for files that no longer exist so the state stays bounded.
    live = {rel for _, rel, _ in files}
    for gone in [k for k in seen if k not in live]:
        del seen[gone]
    _save_seen(seen)

    result = {
        "ok": True,
        "inbox": str(INBOX_DIR),
        "new_items": len(items),
        # Total already-captured items, so "nothing new" doesn't read as "empty"
        # when the inbox actually holds notes/files the user knows are there.
        "total_items": len(files),
        "items": items,
    }
    if overflow:
        result["more_not_shown"] = overflow
        result["note"] = f"{overflow} more new items will come up on the next check"
    if not items:
        if include_all or len(files) == 0:
            result["note"] = "inbox is empty"
        else:
            result["note"] = (
                f"nothing new since the last check, but the inbox still holds "
                f"{len(files)} item{'s' if len(files) != 1 else ''} — say 'what's in my "
                f"inbox' to hear them"
            )
    return result


async def handle_check_inbox(params: FunctionCallParams):
    include_all = bool(params.arguments.get("all", False))
    # The folder scan and file reads are blocking — keep them off the voice loop.
    result = await asyncio.to_thread(check_inbox, include_all)
    logger.info(f"check_inbox(all={include_all}) -> ok={result['ok']} items={result.get('new_items')}")
    await params.result_callback(result)
