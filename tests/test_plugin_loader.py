# tests/test_plugin_loader.py — the plugin contract, end to end:
# load/validate rules (fail-closed, fail-loud, never fatal), the skill_loader
# manifest merge (add-only, core wins), and the shipped dice reference plugin.
import asyncio
import textwrap
from types import SimpleNamespace

from plugin_loader import load_plugins, plugin_load_errors
from skill_loader import load_skills

GOOD_SKILL = textwrap.dedent(
    """\
    ---
    tool: fetch_number
    risk: low
    catalog: fetch a number
    ---
    Body guidance here.
    """
)

GOOD_PLUGIN = textwrap.dedent(
    """\
    from plugin_loader import plugin_tool

    async def handle(params):
        await params.result_callback({"ok": True, "number": 7})

    TOOLS = [
        plugin_tool(
            name="fetch_number",
            description="Fetch the number.",
            properties={},
            required=[],
            handler=handle,
        )
    ]
    """
)


def _write_plugin(root, dirname, skill=GOOD_SKILL, code=GOOD_PLUGIN):
    pdir = root / dirname
    pdir.mkdir(parents=True)
    (pdir / "skill.md").write_text(skill, encoding="utf-8")
    (pdir / "plugin.py").write_text(code, encoding="utf-8")
    return pdir


def test_valid_plugin_loads(tmp_path):
    _write_plugin(tmp_path, "numbers")
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert [p.name for p in loaded] == ["fetch_number"]
    assert loaded[0].risk == "low"
    assert loaded[0].requires_confirmation is False
    assert loaded[0].schema.name == "fetch_number"
    assert plugin_load_errors() == []


def test_missing_risk_rejected(tmp_path):
    skill = GOOD_SKILL.replace("risk: low\n", "")
    _write_plugin(tmp_path, "numbers", skill=skill)
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert loaded == []
    assert len(plugin_load_errors()) == 1
    assert "risk" in plugin_load_errors()[0][1]


def test_high_risk_needs_explicit_confirmation(tmp_path):
    skill = GOOD_SKILL.replace("risk: low", "risk: high")
    _write_plugin(tmp_path, "numbers", skill=skill)
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert loaded == []
    assert "requires_confirmation" in plugin_load_errors()[0][1]


def test_high_risk_with_confirmation_loads(tmp_path):
    skill = GOOD_SKILL.replace(
        "risk: low", "risk: high\nrequires_confirmation: true"
    )
    _write_plugin(tmp_path, "numbers", skill=skill)
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert len(loaded) == 1
    assert loaded[0].risk == "high"
    assert loaded[0].requires_confirmation is True


def test_name_collision_rejected(tmp_path):
    _write_plugin(tmp_path, "numbers")
    loaded = load_plugins(
        reserved_names={"fetch_number"}, plugins_dir=str(tmp_path)
    )
    assert loaded == []
    assert "already exists" in plugin_load_errors()[0][1]


def test_two_plugins_same_name_second_rejected(tmp_path):
    _write_plugin(tmp_path, "a_numbers")
    _write_plugin(tmp_path, "b_numbers")
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert [p.name for p in loaded] == ["fetch_number"]
    assert loaded[0].source == "a_numbers"
    assert plugin_load_errors()[0][0] == "b_numbers"


def test_broken_plugin_skipped_neighbors_still_load(tmp_path):
    _write_plugin(tmp_path, "broken", code="this is not python (")
    _write_plugin(
        tmp_path,
        "working",
        skill=GOOD_SKILL.replace("fetch_number", "other_tool"),
        code=GOOD_PLUGIN.replace("fetch_number", "other_tool"),
    )
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert [p.name for p in loaded] == ["other_tool"]
    assert plugin_load_errors()[0][0] == "broken"


def test_name_mismatch_between_manifest_and_code_rejected(tmp_path):
    _write_plugin(
        tmp_path, "numbers", code=GOOD_PLUGIN.replace('"fetch_number"', '"other_name"')
    )
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert loaded == []
    assert "must agree" in plugin_load_errors()[0][1]


def test_sync_handler_rejected(tmp_path):
    code = GOOD_PLUGIN.replace("async def handle", "def handle").replace(
        "await params.result_callback", "params.result_callback"
    )
    _write_plugin(tmp_path, "numbers", code=code)
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert loaded == []
    assert "async" in plugin_load_errors()[0][1]


def test_multiple_tools_in_one_dir_rejected(tmp_path):
    code = GOOD_PLUGIN.replace("TOOLS = [", "TOOLS = [None] + [")
    _write_plugin(tmp_path, "numbers", code=code)
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert loaded == []
    assert "exactly ONE" in plugin_load_errors()[0][1]


def test_missing_manifest_rejected(tmp_path):
    pdir = tmp_path / "numbers"
    pdir.mkdir()
    (pdir / "plugin.py").write_text(GOOD_PLUGIN, encoding="utf-8")
    loaded = load_plugins(reserved_names=set(), plugins_dir=str(tmp_path))
    assert loaded == []
    assert "skill.md" in plugin_load_errors()[0][1]


# ---- skill_loader merge: plugin manifests join the skills dict, add-only ----


def _core_skill(dirpath, tool):
    dirpath.mkdir(parents=True, exist_ok=True)
    (dirpath / f"{tool}.md").write_text(
        f"---\ntool: {tool}\nrisk: medium\ncatalog: core line\n---\ncore body\n",
        encoding="utf-8",
    )


def test_plugin_skill_merges_into_catalog(tmp_path):
    core = tmp_path / "skills"
    _core_skill(core, "core_tool")
    _write_plugin(tmp_path / "plugins", "numbers")
    skills = load_skills(str(core), plugins_dir=str(tmp_path / "plugins"))
    assert set(skills) == {"core_tool", "fetch_number"}
    assert skills["fetch_number"].catalog == "fetch a number"


def test_plugin_skill_cannot_redefine_core_tool(tmp_path):
    core = tmp_path / "skills"
    _core_skill(core, "core_tool")
    _write_plugin(
        tmp_path / "plugins",
        "impostor",
        skill=GOOD_SKILL.replace("fetch_number", "core_tool"),
        code=GOOD_PLUGIN.replace("fetch_number", "core_tool"),
    )
    skills = load_skills(str(core), plugins_dir=str(tmp_path / "plugins"))
    # Core wins: risk stays medium (the plugin manifest said low), body stays core's.
    assert skills["core_tool"].risk == "medium"
    assert skills["core_tool"].body == "core body"


def test_malformed_plugin_skill_loads_restrictive_under_dir_name(tmp_path):
    core = tmp_path / "skills"
    _core_skill(core, "core_tool")
    pdir = tmp_path / "plugins" / "mystery"
    pdir.mkdir(parents=True)
    (pdir / "skill.md").write_text("no frontmatter at all", encoding="utf-8")
    skills = load_skills(str(core), plugins_dir=str(tmp_path / "plugins"))
    assert skills["mystery"].risk == "high"
    assert skills["mystery"].requires_confirmation is True


# ---- the shipped reference plugin, exercised for real ----


def test_shipped_dice_plugin_loads_and_rolls():
    loaded = load_plugins(reserved_names=set())
    dice = [p for p in loaded if p.name == "roll_dice"]
    assert dice, f"shipped dice plugin failed to load: {plugin_load_errors()}"
    assert dice[0].risk == "low"

    results = []

    async def _roll():
        params = SimpleNamespace(
            arguments={"sides": 6, "count": 2},
            result_callback=None,
        )

        async def capture(payload):
            results.append(payload)

        params.result_callback = capture
        await dice[0].handler(params)

    asyncio.run(_roll())
    assert results and results[0]["ok"] is True
    assert len(results[0]["rolls"]) == 2
    assert all(1 <= r <= 6 for r in results[0]["rolls"])


def test_shipped_dice_plugin_fails_loud_on_bad_args():
    loaded = load_plugins(reserved_names=set())
    dice = [p for p in loaded if p.name == "roll_dice"][0]

    results = []

    async def _roll():
        async def capture(payload):
            results.append(payload)

        await dice.handler(
            SimpleNamespace(arguments={"sides": "many"}, result_callback=capture)
        )

    asyncio.run(_roll())
    assert results[0]["ok"] is False
    assert "whole numbers" in results[0]["error"]
