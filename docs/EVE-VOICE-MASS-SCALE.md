# EVE Voice — Mass-Scale Architecture & Migration Plan

> Goal: EVE voice that works **for anyone, on any network, anywhere** — not just the owner's
> self-hosted PC over a personal Tailscale tailnet. This is the productization path for voice.
> Status: DRAFT v1 (2026-06-23). Owner: a collaborator. Pairs with docs/EVE-JARVIS-MASTERPLAN.md.

## 1. Why today's voice can't scale (root cause, grounded)

Today's voice topology is **single-user, self-hosted, peer-to-peer**:

- `phone_bot.py` runs pipecat's **`SmallWebRTCTransport`** — a *direct* P2P WebRTC connection
  between the phone and the owner's PC, bound to `127.0.0.1:8788`, exposed via
  `tailscale serve :8444`.
- Media (the actual audio RTP) must traverse directly from phone → PC. That requires ICE to
  find a working network path between the two devices.

**Observed failure (2026-06-23):** the phone (`<phone-lan-ip>`) could not reach the PC
(`<pc-lan-ip>`) directly — 100% packet loss — because the PC's Ethernet is classified
"Public" and Windows Firewall drops inbound. Only Tailscale (the machine's tailscale IP) worked. The
phone never even advertises its Tailscale address as an ICE candidate, so the call connected
but no audio flowed. It "worked sometimes" before purely because WebRTC hole-punching is
timing-dependent — a coin flip.

**The architectural verdict:** P2P WebRTC + self-host + per-user tailnet/firewall is a
*one-person* design. It cannot be the mass-market product:
- Every user would need an always-on PC running the stack.
- Every user would need their own private network mesh.
- NAT/firewall traversal is left to chance on every call.

## 2. Target architecture: managed WebRTC transport

The single highest-leverage change: **move voice off self-hosted P2P WebRTC onto managed
WebRTC infrastructure.** The phone connects to a globally-distributed media server (SFU +
TURN), and the EVE bot connects to the *same* room. Neither endpoint needs to reach the other
directly — the cloud media server is always reachable, so NAT/firewall traversal is solved by
construction, for every user, on every network.

**Key enabler:** `phone_bot.py` already runs on **pipecat**, which was built by **Daily**
exactly for this. Swapping the transport is a contained change, not a rewrite:

```
SmallWebRTCTransport(webrtc_connection=...)   →   DailyTransport(room_url, token, ...)
                                              or   LiveKitTransport(url, token, ...)
```

The STT → LLM → TTS pipeline, persona, tools, speaker-ID, half-duplex gate — all unchanged.
Only the transport (how audio gets in/out) changes.

### Daily vs LiveKit (recommendation)

| | **Daily** | **LiveKit** |
|---|---|---|
| pipecat support | Native (first-party) | First-party (supported) |
| Hosting | Managed cloud only | **Managed cloud OR self-host (OSS)** |
| Time-to-working | Fastest (least friction) | Slightly more setup |
| Global TURN/SFU | Yes, included | Yes (cloud); self-host = run your own |
| Cost control at scale | Per-minute, vendor-set | Self-host = infra cost only |
| Privacy posture | Media transits Daily | Self-host = media stays on your infra |

**Recommendation:**
- **Phase A — prove it on Daily.** Fastest path to "voice works anywhere for me + testers."
  Native pipecat integration, zero infra to run. Use it to validate the experience and
  onboarding before spending on scale infra.
- **Phase B — migrate to self-hosted LiveKit for scale.** Aligns with the masterplan's
  **"private-first"** value and gives per-minute economics under your control once volume
  justifies running the infra. Same pipecat transport swap, so Phase A work is not throwaway.

## 3. What actually changes (engineering scope)

### 3a. The bot (`phone_bot.py`)
- Replace `SmallWebRTCTransport` with `DailyTransport`/`LiveKitTransport`.
- Each call = a **room**; the bot joins the room with a server-minted token.
- Drop `tailscale serve :8444` for voice (no longer the media path). Tailscale stays only for
  the owner's self-host/admin convenience.
- The half-duplex `MicGate` can likely be retired — managed SFUs ship real echo cancellation,
  which removes the "no barge-in" trade-off (also relevant to the seamless Phase 2B interrupt
  work and the owner's "she repeats herself" complaint, which is partly half-duplex).

### 3b. New infra
- **Room provisioning + token service:** a small endpoint that, on "start a call," creates a
  room and mints short-lived tokens for (phone, bot). This is also the natural **auth seam** —
  ties directly into seamless **Phase 2A** (per-device credentials, bootstrap codes, argon2).
- **Bot orchestration:** one bot process per active call. Today that's one local process; at
  scale it's a pool of bot workers (containers) that join rooms on demand.

### 3c. The Android app (`eve-app`)
- Replace the `SmallWebRTC` signaling/`WebRtcVoiceClient` with the **Daily** or **LiveKit
  Android SDK**. The app joins a room with a token from the token service instead of POSTing an
  SDP offer to a self-hosted endpoint.
- This is the part that needs a real rebuild + on-device test — but it also *deletes* a pile of
  fragile hand-rolled ICE/SDP/disposal code (the same code that caused this session's
  on-device audio confusion).

### 3d. Compute at scale (the other bottleneck)
- STT/LLM/TTS cannot all sit on one GPU for thousands of users. Per the masterplan's
  **"hybrid, not pure-local"** call:
  - Cloud inference (or autoscaled GPU workers) for the voice pipeline at scale.
  - Local/private models reserved for privacy-sensitive paths.
- Bot workers become stateless-per-call; conversation/memory state lives in the canonical
  backend (seamless Phase 3), not in the bot process.

## 4. Cost model (order-of-magnitude, to validate before committing)

Per active voice-minute at scale, the real line items:
- **Media (Daily/LiveKit):** ~cents/participant-minute managed; ~infra-only if self-hosted.
- **STT + LLM + TTS inference:** the dominant cost; depends on model choice (cloud API vs
  self-hosted GPU). This is where unit economics live or die — model before scaling.
- **Bot worker compute:** CPU/GPU per concurrent call.

Action: build a one-page unit-economics sheet (cost per 1k users × avg minutes) **before**
committing to a provider. Don't scale infra ahead of validated per-user economics.

## 5. Migration on-ramp (don't break the owner's daily driver)

1. **Now:** keep the self-hosted `SmallWebRTC` path for the owner (firewall-fixed) so daily
   testing continues uninterrupted.
2. **Phase A:** add a `JARVIS_VOICE_TRANSPORT=daily|smallwebrtc` switch in `phone_bot.py`.
   Stand up Daily, mint tokens, prove a call from the phone over cellular (true "anywhere"
   test). Owner can flip between local and Daily.
3. **Phase A.5:** rebuild `eve-app` with the Daily SDK behind a build flag; on-device test on
   Wi-Fi, cellular, and a foreign network.
4. **Phase B:** stand up self-hosted LiveKit; switch the transport flag; load-test.
5. **Throughout:** the token/room service is the wedge for multi-tenant auth — build it on the
   seamless **Phase 2A** identity contracts so it's multi-user from day one.

## 6. How this fits the existing plan

This is **not a detour** from the seamless integration — it's the voice-transport half of
productization that the seamless plan didn't cover:
- **Phase 2A (identity/credentials)** = the auth foundation the token/room service needs.
- **Phase 2B (interrupt/barge-in)** = naturally solved by managed-SFU echo cancellation.
- **Phase 3 (canonical state)** = lets bot workers be stateless-per-call (required for a worker
  pool).

So: finish gating Phase 1 (done), let ARCH re-confirm 2A, and slot this voice-transport track
alongside — they converge on the same multi-user backend.

## 7. Open questions for the collaborator

1. **Beachhead:** who are the first 100 users, and what's the one massive problem EVE solves
   for them? (Drives whether we optimize for cost, latency, or privacy first.)
2. **Daily vs LiveKit for Phase A:** fastest-to-prove (Daily) vs build-on-self-hostable from
   the start (LiveKit). Recommended: Daily to prove, LiveKit to scale.
3. **Compute:** which models, hosted where? This sets unit economics — decide before scaling.
4. **Privacy line:** what *must* stay on-device/private vs what can be cloud inference?
