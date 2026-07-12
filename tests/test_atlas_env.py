import subprocess
import sys
from pathlib import Path

import atlas_env

REPO = Path(__file__).resolve().parent.parent


def test_atlas_var_fans_into_both_legacy_prefixes():
    env = {"ATLAS_MODE": "local"}
    filled = atlas_env.apply_aliases(env)
    assert env["EVE_MODE"] == "local"
    assert env["JARVIS_MODE"] == "local"
    assert filled == 2


def test_explicit_legacy_var_always_wins():
    env = {"ATLAS_APP_TOKEN": "new", "EVE_APP_TOKEN": "old"}
    atlas_env.apply_aliases(env)
    assert env["EVE_APP_TOKEN"] == "old"
    assert env["JARVIS_APP_TOKEN"] == "new"


def test_non_atlas_vars_untouched():
    env = {"OLLAMA_MODEL": "qwen3:8b", "ATLASSIAN_TOKEN": "x"}
    atlas_env.apply_aliases(env)
    assert env == {"OLLAMA_MODEL": "qwen3:8b", "ATLASSIAN_TOKEN": "x"}


def test_bare_atlas_prefix_ignored():
    env = {"ATLAS_": "x"}
    atlas_env.apply_aliases(env)
    assert env == {"ATLAS_": "x"}


def test_no_suffix_is_shared_between_eve_and_jarvis_in_the_codebase():
    # The blanket fan-out is only safe while EVE_<X> and JARVIS_<X> never
    # coexist for the same <X>. Guard that invariant against future vars.
    out = subprocess.run(
        ["grep", "-rhoE", r"\b(EVE|JARVIS)_[A-Z0-9_]+\b",
         "--include=*.py", "--exclude-dir=app", "--exclude-dir=.venv",
         "--exclude-dir=__pycache__", "--exclude-dir=eve-app",
         "--exclude-dir=glasses", "--exclude-dir=tests", "."],
        cwd=REPO, capture_output=True, text=True,
    ).stdout.split()
    suffixes = {}
    for name in set(out):
        prefix, _, suffix = name.partition("_")
        suffixes.setdefault(suffix, set()).add(prefix)
    shared = {s for s, p in suffixes.items() if p == {"EVE", "JARVIS"}}
    assert not shared, f"EVE_/JARVIS_ share suffixes (alias fan-out unsafe): {shared}"


def test_entrypoints_apply_aliases_after_dotenv():
    # Every entrypoint that loads .env must also apply the ATLAS_* aliases.
    for entry in ["bot.py", "phone_bot.py", "watch_bot.py", "jetson_bot.py",
                  "approval_api.py"]:
        src = (REPO / entry).read_text()
        assert "atlas_env" in src and "apply_aliases" in src, (
            f"{entry} loads .env but never applies ATLAS_* aliases"
        )


def test_alias_visible_to_child_reading_os_environ():
    # End-to-end through a real interpreter: ATLAS_ var set outside, legacy
    # name visible after apply_aliases() on the real os.environ.
    code = (
        "import atlas_env, os; atlas_env.apply_aliases(); "
        "print(os.environ['JARVIS_ASSISTANT_NAME'])"
    )
    out = subprocess.run(
        [sys.executable, "-c", code], cwd=REPO, capture_output=True, text=True,
        env={"PATH": "/usr/bin:/bin", "ATLAS_ASSISTANT_NAME": "Atlas"},
    )
    assert out.stdout.strip() == "Atlas", out.stderr
