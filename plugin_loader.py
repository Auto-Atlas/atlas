# plugin_loader.py
#
# Third-party tool plugins for Atlas. A plugin is a directory under plugins/:
#
#   plugins/<plugin_name>/
#     plugin.py    — defines TOOLS = [plugin_tool(...)] (exactly one tool, v1)
#     skill.md     — frontmatter policy manifest (tool, risk, catalog, ...) + body
#
# Loaded ONCE at jarvis_core import time, appended to ALL_TOOL_SCHEMAS before
# build_context() snapshots the list (the delegate precedent), and registered
# through the SAME dedupe → policy → handler gate stack as every core tool.
#
# Loading is per-plugin fail-loud, fail-closed, never fatal:
#   * a broken plugin is SKIPPED with a logger.error and recorded in
#     plugin_load_errors() — it never crashes the voice loop's boot;
#   * a plugin whose skill.md omits an explicit `risk:` is REJECTED — the
#     permissive no-skill fallback core tools enjoy does not apply to
#     third-party code (a forgotten manifest must not mean "ungated");
#   * a plugin whose tool name collides with a core tool, a delegate, or an
#     earlier plugin is REJECTED — llm.register_function would silently
#     overwrite, and two same-name schemas confuse the model.
#
from __future__ import annotations

import importlib.util
import inspect
import re
from dataclasses import dataclass
from pathlib import Path

import yaml
from loguru import logger
from pipecat.adapters.schemas.function_schema import FunctionSchema

_NAME_RE = re.compile(r"^[a-z][a-z0-9_]{2,40}$")
_RISKS = ("low", "medium", "high")

# Filled by load_plugins(); read by plugin_load_errors() and tests.
_LOAD_ERRORS: list[tuple[str, str]] = []


@dataclass(frozen=True)
class PluginTool:
    """One loadable tool. Built via plugin_tool() inside a plugin's plugin.py."""

    name: str
    schema: FunctionSchema
    handler: object                      # async (FunctionCallParams) -> None
    # Structural required-args gate, enforced by tool_policy before the handler
    # runs (same mechanism as jarvis_core._REQUIRED_FIELDS, but each plugin
    # carries its own — plugins cannot edit core maps).
    requires_fields: tuple[str, ...] = ()
    source: str = ""                     # plugin dir name, for error messages
    # Policy values from the plugin's OWN validated skill.md — registration
    # builds the ToolPolicy from these, never from a name lookup, so a plugin
    # is always gated by exactly what its manifest declared. Defaults are the
    # fail-safe maximum; load_plugins overwrites them with the parsed values.
    risk: str = "high"
    requires_confirmation: bool = True


def plugin_tool(
    *,
    name: str,
    description: str,
    properties: dict | None = None,
    required: list[str] | None = None,
    handler,
    requires_fields: tuple[str, ...] = (),
) -> PluginTool:
    """The one constructor a plugin author calls. Mirrors FunctionSchema plus
    the structural-args gate. Validation happens in load_plugins(), not here,
    so a bad value produces a recorded load error instead of an import crash."""
    return PluginTool(
        name=name,
        schema=FunctionSchema(
            name=name,
            description=description,
            properties=properties or {},
            required=required or [],
        ),
        handler=handler,
        requires_fields=tuple(requires_fields),
    )


def plugin_load_errors() -> list[tuple[str, str]]:
    """(plugin_dir_name, reason) for every plugin that failed to load this boot."""
    return list(_LOAD_ERRORS)


def _parse_frontmatter(path: Path) -> dict:
    """Frontmatter of a plugin's skill.md, strictly. Raises on any malformation —
    unlike skill_loader's lenient core path, a plugin manifest must parse."""
    text = path.read_text(encoding="utf-8", errors="replace")
    if not text.startswith("---"):
        raise ValueError("skill.md has no frontmatter block")
    _, fm, _ = text.split("---", 2)
    data = yaml.safe_load(fm) or {}
    if not isinstance(data, dict):
        raise ValueError("skill.md frontmatter is not a mapping")
    return data


def _validate(fm: dict, tools: object) -> PluginTool:
    """All the reject rules in one place. Returns the single validated tool."""
    tool_name = str(fm.get("tool", "")).strip()
    if not tool_name:
        raise ValueError("skill.md frontmatter must declare `tool:`")
    if not _NAME_RE.match(tool_name):
        raise ValueError(
            f"tool name {tool_name!r} must match {_NAME_RE.pattern} "
            "(lowercase snake_case, 3-41 chars)"
        )
    if "risk" not in fm:
        raise ValueError(
            "skill.md must declare an explicit `risk:` (low|medium|high) — "
            "plugins never inherit the permissive no-skill default"
        )
    risk = str(fm["risk"]).strip()
    if risk not in _RISKS:
        raise ValueError(f"risk {risk!r} is not one of {_RISKS}")
    if risk == "high" and "requires_confirmation" not in fm:
        raise ValueError(
            "high-risk plugins must state `requires_confirmation:` explicitly"
        )
    if not isinstance(tools, list) or len(tools) != 1:
        raise ValueError(
            "plugin.py must define TOOLS as a list of exactly ONE plugin_tool() "
            "(one plugin directory = one tool; ship a second directory for a second tool)"
        )
    ptool = tools[0]
    if not isinstance(ptool, PluginTool):
        raise ValueError("TOOLS[0] is not a plugin_tool(...) instance")
    if ptool.name != tool_name:
        raise ValueError(
            f"TOOLS[0].name {ptool.name!r} != skill.md tool {tool_name!r} — "
            "the manifest and the code must agree on the name"
        )
    if not inspect.iscoroutinefunction(ptool.handler):
        raise ValueError(
            f"handler for {tool_name!r} must be `async def` "
            "(it receives FunctionCallParams and must await params.result_callback)"
        )
    return ptool


def load_plugins(
    *,
    reserved_names: set[str],
    plugins_dir: str | None = None,
) -> list[PluginTool]:
    """Scan plugins/*/ and return the validated tools. Anchored to this file's
    directory by default (the skill_loader precedent — cwd differs under
    systemd). Every failure lands in plugin_load_errors() and a logger.error;
    a broken plugin never stops its neighbors or the boot."""
    _LOAD_ERRORS.clear()
    root = Path(plugins_dir) if plugins_dir else Path(__file__).parent / "plugins"
    loaded: list[PluginTool] = []
    if not root.is_dir():
        return loaded

    taken = set(reserved_names)
    for pdir in sorted(p for p in root.iterdir() if p.is_dir()):
        try:
            plugin_py = pdir / "plugin.py"
            skill_md = pdir / "skill.md"
            if not plugin_py.is_file():
                raise ValueError("missing plugin.py")
            if not skill_md.is_file():
                raise ValueError("missing skill.md (the policy manifest is mandatory)")
            fm = _parse_frontmatter(skill_md)

            spec = importlib.util.spec_from_file_location(
                f"atlas_plugins.{pdir.name}", plugin_py
            )
            if spec is None or spec.loader is None:
                raise ValueError("plugin.py is not importable (no module spec)")
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)

            ptool = _validate(fm, getattr(module, "TOOLS", None))
            if ptool.name in taken:
                raise ValueError(
                    f"tool name {ptool.name!r} already exists (core tool, delegate, "
                    "or another plugin) — refusing to shadow it"
                )
            taken.add(ptool.name)
            loaded.append(
                PluginTool(
                    risk=str(fm["risk"]).strip(),
                    requires_confirmation=bool(fm.get("requires_confirmation", False)),
                    name=ptool.name,
                    schema=ptool.schema,
                    handler=ptool.handler,
                    requires_fields=ptool.requires_fields,
                    source=pdir.name,
                )
            )
            logger.info(f"plugin {pdir.name}: loaded tool {ptool.name!r} (risk={fm['risk']})")
        except Exception as e:
            _LOAD_ERRORS.append((pdir.name, str(e)))
            logger.error(f"plugin {pdir.name}: REJECTED — {e}")

    if _LOAD_ERRORS:
        names = ", ".join(n for n, _ in _LOAD_ERRORS)
        logger.error(
            f"plugins: {len(loaded)} loaded, {len(_LOAD_ERRORS)} FAILED ({names}) — "
            "failed plugins are OFF this boot; fix and restart"
        )
    return loaded
