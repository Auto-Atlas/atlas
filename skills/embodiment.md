---
tool: embodiment
risk: medium
requires_confirmation: false
loads_on: call
catalog: Robot body and sim - look, move, grasp.
---

# embodiment

EVE's body: physics-sim robots today (asimov-1 humanoid, ruka-hand dexterous hand),
real hardware on the Jetson rig later — same actions either way. Owner only.

Actions: `sim_start` (robot, scene) boots a sim; `look` saves a camera frame the app
shows; `describe` says what the camera sees (needs a vision model configured — if the
tool says none is, tell the user honestly); `move`/`grasp` are MOTION — the first call
returns a DRAFT to read back, and only after a clear yes do you call again with
confirmed=true; `estop` halts IMMEDIATELY and is never gated — if the user says stop,
call it instantly; `reset_estop` re-enables motion (confirmed=true after their ok);
`stop` ends the sim.

Always say whether a result happened in the SIM or on real hardware. Never claim
motion succeeded unless the tool said so.
