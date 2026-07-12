# jarvis-sidecar — EVE

EVE: self-hosted voice assistant (pipecat voice loop + FastAPI approval hub +
Android/desktop apps + agent-delegation fabric). **EVE is a product**: never
hardcode owner-specific values (names, URLs, tokens, phone numbers) — every
knob is an env var or onboarding config.

## Layout (what runs from where)
- Repo root `*.py` = the sidecar: voice loops (`bot.py` desktop, `phone_bot.py`
  phone), `approval_api.py` hub (:8799, bearer token), tools (`*_tool.py`),
  spools. `jarvis_core.py` registers tools; `tool_policy.py` gates them.
- `app/` = OpenJarvis fork, served by the `jarvis-server` systemd unit (:8000)
  via PYTHONPATH — app-code changes go live on service restart, no pip.
- `eve-app/android/` = the Android app (gradle). `glasses/mentra-eve/` =
  MentraOS bridge (TypeScript). `deploy/` = systemd unit templates.
- `openjarvis/`, `_bmad*` = vendored/foreign — do not lint, do not refactor.

## Hard rules
- **Never restart, kill, or reconfigure running services** (jarvis-server,
  eve-vlm, voice loops) without explicit permission — ask and say why.
- `main` is the only long-lived branch. Tag `pre-<thing>-<date>` before risky
  work. Small verified increments; suite green EVERY commit.
- Another Claude session may share this working tree: never touch files that
  are already dirty from someone else's work; check `git status` before bulk
  operations and keep your commits scoped to files you changed.
- Never `npm i -g @anthropic-ai/claude-code` on this box (shadows the real CLI).

## Test gates (run before any commit)
- Python: `.venv/bin/python -m pytest tests/ -q` (ALWAYS the venv; scoped by
  pytest.ini — a bare `pytest` elsewhere collects 7000 foreign tests).
- TS bridge: `cd glasses/mentra-eve && npm run build && npm test`.
- Android: `cd eve-app/android && ./gradlew testDebugUnitTest assembleDebug`
  (needs JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64).
- Lint: ruff (config in pyproject.toml) auto-runs on edits via the PostToolUse
  hook; `.venv/bin/ruff check .` for a full pass.

## Code conventions (match these exactly)
- Every module opens with a narrative comment block: what it is, the flow
  across process boundaries, and its invariants (see vision_frames.py,
  vision_tool.py). Comments state constraints code can't show — never "what
  the next line does".
- **Failure honesty**: tool results carry an `instruction` field telling EVE
  exactly what to SAY, and every failure names WHICH leg failed (app not
  connected / no frame arrived / model down) — never a generic "it failed".
- Spool/config modules that cross process boundaries are stdlib-only imports;
  processes meet on disk (tmp-write + os.replace atomic), never import each
  other. Hostile-input discipline: ids are validated hex before becoming
  filenames.
- New voice tool = FunctionSchema + handler in its own module, registered in
  jarvis_core.py (schema list + handler map), gated in tool_policy.py, plus a
  skills/<name>.md catalog entry. Pattern: vision_tool.py.
- Tests mirror tests/test_vision_phone.py: spool tests, FastAPI TestClient
  endpoint tests (auth + validation + caps), monkeypatched tool tests
  asserting the exact failure instruction per leg.

## Session habits
- Subagent coders/implementers run on Opus.
- Real memory lives in the obsidian wiki (JARVIS_MEMORY_PAGE in .env) and
  ~/.claude project memory — check both before re-deriving project state.
