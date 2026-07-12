# skill_loader.py
#
# Loads skills/*.md — one per tool — splitting the leading `---` frontmatter
# block (parsed with yaml.safe_load) from the markdown body. Produces:
#   * skill_catalog(): short "- tool: one-liner" lines for the base prompt,
#     replacing the per-tool block that used to live in persona.py.
#   * skill_body(tool): the full body, attached to a tool's first result by
#     tool_policy so the model gets behavioral guidance only on use.
#
# Enforcement NEVER reads this file at runtime — code does (tool_policy). A
# frontmatter parse error loads the skill with the MOST restrictive defaults so
# it can only over-confirm, never silently open a gate.
#
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml
from loguru import logger


@dataclass(frozen=True)
class Skill:
    tool: str
    risk: str
    requires_confirmation: bool
    loads_on: str
    catalog: str
    body: str


def _split_frontmatter(text: str) -> tuple[dict, str]:
    """Return (frontmatter_dict, body). Raises if the block is malformed."""
    if not text.startswith("---"):
        raise ValueError("no frontmatter block")
    _, fm, body = text.split("---", 2)
    data = yaml.safe_load(fm) or {}
    if not isinstance(data, dict):
        raise ValueError("frontmatter is not a mapping")
    return data, body.strip()


def load_skills(directory: str | None = None) -> dict[str, Skill]:
    # Anchor to THIS file's directory, not cwd (BMAD: Winston). EVE may launch
    # from another cwd (Tauri/systemd); a relative "skills" glob would silently
    # find nothing and EVE would run with an empty catalog and no guidance.
    if directory is None:
        directory = str(Path(__file__).parent / "skills")
    skills: dict[str, Skill] = {}
    for path in sorted(Path(directory).glob("*.md")):
        text = path.read_text(encoding="utf-8", errors="replace")
        try:
            fm, body = _split_frontmatter(text)
            tool = str(fm["tool"])
            skills[tool] = Skill(
                tool=tool,
                risk=str(fm.get("risk", "low")),
                requires_confirmation=bool(fm.get("requires_confirmation", False)),
                loads_on=str(fm.get("loads_on", "call")),
                catalog=str(fm.get("catalog", "")).strip(),
                body=body,
            )
        except Exception as e:
            # Fail loud, fail SAFE: recover the tool name (best-effort scan of a
            # `tool:` line so the broken skill keys under the tool it claims to be,
            # else the filename) and lock it to the most restrictive policy so a
            # broken skill can't open a gate.
            tool = path.stem
            for line in text.splitlines():
                stripped = line.strip()
                if stripped.startswith("tool:"):
                    candidate = stripped[len("tool:"):].strip()
                    if candidate:
                        tool = candidate
                    break
            logger.warning(f"skill {path.name} failed to parse ({e}); loading fail-safe restrictive")
            skills[tool] = Skill(
                tool=tool, risk="high", requires_confirmation=True,
                loads_on="call", catalog="", body=text,
            )
    return skills


def skill_catalog(skills: dict[str, Skill]) -> str:
    lines = [f"- {s.tool}: {s.catalog}" for s in skills.values() if s.catalog]
    return "\n".join(lines)


def skill_body(skills: dict[str, Skill], tool: str) -> str | None:
    s = skills.get(tool)
    return s.body if s else None
