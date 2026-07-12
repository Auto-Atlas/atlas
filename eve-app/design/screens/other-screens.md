# Screens: Status, Activity, Memory, Talk

## Status / health (carries the activation toggle)
`StatusTile`s in a grid: sidecar up, pending count, **releasing-unverified count**
(reconciliation, from `/v1/health.releasing_orphans`), bridge live, "N requests expired
unseen", host/GPU if cheap. Below: the **remote-approval activation toggle** (`EveSwitch`)
wired to `POST /v1/settings {remote_approval_enabled}` with the plain-language consequence
line: *"Known family members' high-risk requests will reach you for approval instead of
being blocked."* This is the deliberate opt-in front door (spec §1.9) — owner-authenticated
by the app token, over the tailnet.

## Activity / transcripts
Day digest from `GET /v1/activity?day=today` — exchanges, tools (calls/failed), tool
failures, latency (avg/worst LLM TTFB) — plus a recent transcript tail. `src` tag
distinguishes desktop vs phone turns. Read-only.

## Memory
List facts for a speaker bucket (`GET /v1/memory?speaker=`). Add a fact (`POST /v1/memory
{speaker, fact}`) — **explicit speaker** required (spec §1.8; the REST path has no voice
turn, so it writes the per-speaker page directly, never the owner default).

## Talk-to-EVE (Phase 2 of this app — not built in run 1)
Push-to-talk over native WebRTC reusing `phone_bot`'s SmallWebRTC loop; the `ListeningOrb`
(breathing pulse + waveform) for live state; live partial transcript. Deferred because it
needs `phone_bot` to expose its own signaling endpoint for `/v1/voice/offer` to forward to
(there is no stable cross-process handle to the per-session transport). Will be built real,
never stubbed. The bottom-nav entry renders disabled with a "coming soon" affordance.

## Bottom nav
Approvals (hero) · Talk (disabled, Phase 2) · Activity · Memory · Status.
Icons from Lucide-equivalent vectors; active item in `accent` (teal).
