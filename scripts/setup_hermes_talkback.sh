#!/usr/bin/env bash
# Register EVE's talk-back MCP server in Hermes's config (idempotent) and set its per-server
# tool timeout to 900s so ask_eve can block for the full answer window. Prints every action;
# touches no secrets (the webhook path token is read locally, never echoed).
# Usage: scripts/setup_hermes_talkback.sh
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
TOKEN="$(cat "$REPO/webhook_token.txt")"
URL="http://127.0.0.1:8787/agent/a2a/${TOKEN}"

if hermes mcp list 2>/dev/null | grep -qE '^\s*eve\s'; then
  echo "hermes MCP server 'eve' already registered"
else
  echo "registering MCP server 'eve' -> $REPO/eve_talkback_mcp.py (inbound: :8787 path token)"
  # NOTE: --args is greedy ("must be the last option") — --env MUST come before it.
  echo "Y" | hermes mcp add eve \
    --command "$REPO/.venv/bin/python" \
    --env "EVE_TALKBACK_INBOUND_URL=${URL}" "EVE_TALKBACK_ASK_WAIT_S=840" \
    --args "$REPO/eve_talkback_mcp.py"
fi

echo "setting mcp_servers.eve.timeout=900 in ~/.hermes/config.yaml"
"$REPO/.venv/bin/python" - <<'EOF'
import os, yaml
p = os.path.expanduser("~/.hermes/config.yaml")
with open(p) as f:
    cfg = yaml.safe_load(f) or {}
srv = cfg.get("mcp_servers", {}).get("eve")
if srv is None:
    raise SystemExit("mcp_servers.eve not found — did `hermes mcp add eve` succeed?")
if srv.get("timeout") != 900:
    srv["timeout"] = 900
    with open(p, "w") as f:
        yaml.safe_dump(cfg, f, sort_keys=False)
    print("timeout set to 900")
else:
    print("timeout already 900")
EOF
echo "done — new hermes sessions can call notify_eve / ask_eve"
