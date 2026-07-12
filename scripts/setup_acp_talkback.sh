#!/usr/bin/env bash
# setup_acp_talkback.sh — give ACP-driven Claude Code sessions mid-task talk-back
# to EVE (notify_eve / ask_eve), mirroring scripts/setup_hermes_talkback.sh.
#
# Writes <repo>/acp-talkback.mcp.json (gitignored — it embeds the webhook token)
# and prints the JARVIS_ACP_TALKBACK_CLAUDE_ARGS line to add to .env. The acp
# brain then mints a per-task callback token per delegation and the Claude Code
# session gets EVE's talkback MCP server via --mcp-config.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOKEN_FILE="$REPO/webhook_token.txt"
if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "ERROR: $TOKEN_FILE not found — EVE's A2A inbound is not set up yet." >&2
  exit 1
fi
TOKEN="$(cat "$TOKEN_FILE")"
# The /agent/a2a/<token> route lives on the sms_webhook app (JARVIS_SMS_WEBHOOK_PORT,
# default 8787) — NOT on the A2A adapter (EVE_A2A_PORT, default 8790), which has no
# such route. Using the adapter var here silently registered talk-back against a 404.
URL="http://127.0.0.1:${JARVIS_SMS_WEBHOOK_PORT:-8787}/agent/a2a/${TOKEN}"
OUT="$REPO/acp-talkback.mcp.json"

python3 - "$OUT" "$REPO" "$URL" <<'PY'
import json, sys
out, repo, url = sys.argv[1:4]
with open(out, "w") as fh:
    json.dump({"mcpServers": {"eve": {
        "command": "python3",
        "args": [f"{repo}/eve_talkback_mcp.py"],
        "env": {"EVE_TALKBACK_INBOUND_URL": url,
                "EVE_TALKBACK_ASK_WAIT_S": "840"},
    }}}, fh, indent=2)
    fh.write("\n")
PY
chmod 600 "$OUT"

echo "Wrote $OUT"
echo
# Comma form (one token): --allowedTools is variadic — space-separated names
# would swallow following positionals.
echo "Add to $REPO/.env:"
echo "JARVIS_ACP_TALKBACK_CLAUDE_ARGS=--mcp-config $OUT --allowedTools mcp__eve__notify_eve,mcp__eve__ask_eve"
