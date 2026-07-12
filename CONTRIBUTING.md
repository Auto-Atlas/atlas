# Contributing to Atlas

Thanks for wanting to help. Atlas is young and moving fast; small, verified
changes land quickest.

## Ground rules

- **Every change runs the tests.** The python suite must be green:
  `.venv/bin/pytest tests/` (CI runs exactly this). If you touch
  `glasses/mentra-eve`, run `npm test` there; if you touch
  `eve-app/android`, run `./gradlew testDebugUnitTest` there.
- **No silent fallbacks.** A failure must be loud — an error the user sees or
  a log line. Code that pretends to work when a dependency is missing will
  not be merged.
- **No personal data in the tree.** Personal config lives in gitignored files
  (`.env`, `business_context.md`, `*.local.json`) or files pointed at by env
  vars. Templates that ship in the repo must be neutral. CI runs a gitleaks
  scan on every PR.
- **New env vars use the `ATLAS_` prefix.** The legacy `EVE_`/`JARVIS_`
  names keep working through `atlas_env.py`, but new configuration should be
  introduced as `ATLAS_*` only.

## Repo layout in one minute

- Repo root — the voice loops (`bot.py` desktop, `phone_bot.py` phone,
  `watch_bot.py` watch server), the tool layer (`*_tool.py`), the agent
  fabric (`a2a_fabric.py`, `agent_*.py`), and the approval hub
  (`approval_api.py`). Tests in `tests/`.
- `app/` — vendored fork of upstream OpenJarvis (Apache-2.0, see NOTICE).
  Keep changes there minimal and upstream-shaped.
- `eve-app/` — native clients (Android, iOS) and design assets.
- `glasses/mentra-eve` — the MentraOS smart-glasses bridge (TypeScript).
- The Wear OS watch app lives in its own repo:
  [atlas-watch](https://github.com/Auto-Atlas/atlas-watch).

## Workflow

1. Fork, branch from `main`.
2. Make the change, with tests. Python style is enforced by ruff
   (`.venv/bin/ruff check .` — config in `pyproject.toml`).
3. Open a PR describing what changed and how you verified it (paste the test
   run). PRs that only say "should work" will be sent back for evidence.

## Reporting bugs

Open an issue with: what you did, what happened (paste the actual log —
Atlas logs loudly on purpose), what you expected, OS, and Python version.
For anything security-sensitive, see [SECURITY.md](SECURITY.md) — do not open
a public issue.
