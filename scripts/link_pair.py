#!/usr/bin/env python3
# scripts/link_pair.py — secure pairing for the standing agent link (Hermes -> EVE).
#
# One command mints ONE long-lived link key and installs it on BOTH sides atomically:
#   - EVE side:    EVE_AGENT_LINK_KEY=<key> in jarvis-sidecar/.env
#   - Hermes side: mcp_servers.eve.env.EVE_AGENT_LINK_KEY in ~/.hermes/config.yaml
#     (line surgery — comments and formatting in the YAML are preserved)
# plus installs/refreshes the eve-link skill into ~/.hermes/skills/eve-link/.
#
# The key is never printed in full (masked), never spoken, and never leaves this box.
# Re-running the script ROTATES the key on both sides in one shot — that is the revocation
# story: pair again and every holder of the old key is locked out.
#
# After pairing, restart the services that cache the env:
#   systemctl --user restart atlas-sidecar.service
# (hermes spawns the MCP server per session, so it picks the new key up on its next chat.)
#
# stdlib only.
import re
import secrets
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ENV_FILE = REPO / ".env"
HERMES_CONFIG = Path.home() / ".hermes" / "config.yaml"
SKILL_SRC = REPO / "deploy" / "hermes-skills" / "eve-link"
SKILL_DST = Path.home() / ".hermes" / "skills" / "eve-link"

ENV_KEY = "EVE_AGENT_LINK_KEY"


def set_env_line(path: Path, key: str, value: str):
    lines = path.read_text().splitlines() if path.exists() else []
    pat = re.compile(rf"^{re.escape(key)}=")
    out, done = [], False
    for ln in lines:
        if pat.match(ln):
            out.append(f"{key}={value}")
            done = True
        else:
            out.append(ln)
    if not done:
        out.append(f"{key}={value}")
    path.write_text("\n".join(out) + "\n")


def set_hermes_mcp_env(path: Path, key: str, value: str):
    """Insert/replace `      KEY: value` inside mcp_servers.eve.env — pure line surgery so
    every comment and unrelated line in the YAML survives byte-for-byte."""
    text = path.read_text()
    lines = text.splitlines()
    # locate the env: block of mcp_servers.eve
    in_mcp = in_eve = False
    env_start, env_indent = None, 0
    for i, ln in enumerate(lines):
        if re.match(r"^mcp_servers:\s*$", ln):
            in_mcp, in_eve = True, False
            continue
        if in_mcp and re.match(r"^\S", ln):        # left the mcp_servers block
            in_mcp = in_eve = False
        if in_mcp and re.match(r"^  eve:\s*$", ln):
            in_eve = True
            continue
        if in_mcp and in_eve and re.match(r"^  \S", ln):   # next server
            in_eve = False
        if in_mcp and in_eve and re.match(r"^\s+env:\s*$", ln):
            env_start = i
            env_indent = len(ln) - len(ln.lstrip()) + 2
            break
    if env_start is None:
        raise SystemExit(f"could not find mcp_servers.eve.env in {path} — is the talk-back "
                         "MCP server registered? (hermes mcp list)")
    pad = " " * env_indent
    entry = f"{pad}{key}: {value}"
    # replace an existing key inside the env block, else insert right after `env:`
    j, replaced = env_start + 1, False
    while j < len(lines) and lines[j].startswith(pad):
        if lines[j].lstrip().startswith(f"{key}:"):
            lines[j] = entry
            replaced = True
            break
        j += 1
    if not replaced:
        lines.insert(env_start + 1, entry)
    backup = path.with_suffix(path.suffix + ".bak-link-pair")
    shutil.copy2(path, backup)
    path.write_text("\n".join(lines) + ("\n" if text.endswith("\n") else ""))
    return backup


def install_skill():
    if not SKILL_SRC.is_dir():
        return False
    SKILL_DST.mkdir(parents=True, exist_ok=True)
    for f in SKILL_SRC.iterdir():
        shutil.copy2(f, SKILL_DST / f.name)
    return True


def main():
    if not HERMES_CONFIG.exists():
        raise SystemExit(f"{HERMES_CONFIG} not found — install/configure hermes first")
    key = secrets.token_urlsafe(32)
    set_env_line(ENV_FILE, ENV_KEY, key)
    backup = set_hermes_mcp_env(HERMES_CONFIG, ENV_KEY, key)
    skill = install_skill()
    print(f"paired: standing link key rotated ({key[:4]}…{key[-4:]}, 43 chars)")
    print(f"  EVE side   : {ENV_FILE} ({ENV_KEY})")
    print(f"  Hermes side: {HERMES_CONFIG} (mcp_servers.eve.env) — backup at {backup}")
    print(f"  skill      : {'installed -> ' + str(SKILL_DST) if skill else 'source missing, skipped'}")
    print("now restart EVE so the voice loop sees the key:")
    print("  systemctl --user restart atlas-sidecar.service")


if __name__ == "__main__":
    sys.exit(main())
