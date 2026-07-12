# Self-hosted ntfy — EVE's push-notification channel

Deployed 2026-07-02 (owner-approved). No cloud account: the ntfy server runs in Docker on
loopback, EVE publishes to it locally, and the phone subscribes over the tailnet.

## Server (this box)

```bash
docker run -d --name eve-ntfy --restart unless-stopped \
  -p 127.0.0.1:8092:80 \
  -v eve-ntfy-cache:/var/cache/ntfy \
  -e NTFY_BASE_URL=https://your-host.<tailnet>.ts.net:9443 \
  -e NTFY_CACHE_FILE=/var/cache/ntfy/cache.db \
  -e NTFY_BEHIND_PROXY=true \
  binwiederhier/ntfy serve

# tailnet HTTPS front door (9443 — 443/8443/8444 were already in use):
tailscale serve --https=9443 --bg http://127.0.0.1:8092

curl -s http://127.0.0.1:8092/v1/health                       # {"healthy":true}
curl -s https://your-host.<tailnet>.ts.net:9443/v1/health     # reachable from the tailnet
```

`.env` (already set; topic value stays out of git):

```
EVE_NTFY_URL=http://127.0.0.1:8092    # EVE publishes on loopback
EVE_NTFY_TOPIC=eve-approvals-<random> # random suffix = the subscription capability
```

## Phone (one minute)

1. Install **ntfy** (by Philipp Heckel) from the Play Store.
2. In the app: **⚙ Settings → Default server** → `https://your-host.<tailnet>.ts.net:9443`
   (the phone must be on the tailnet — the Tailscale app connected).
3. **+ Subscribe to topic** → enter the exact `EVE_NTFY_TOPIC` value from `.env`.
4. Done. Agent questions ("EVE — hermes needs your answer"), blockers, approvals, and calendar
   reminders now buzz the phone with the actual content; tapping **Review** deep-links the EVE
   app's approval card.

## What rides this channel

Everything `approval_push.notify` carries: staged approvals, agent talk-back questions (highest
priority, with the voice command to answer), agent blockers/results during quiet hours or when
no session is live, and `calendar_watch` reminders/look-aheads. If ntfy is ever down, EVE
escalates to the OpenJarvis Telegram bridge (when configured) and otherwise queues updates for
session-start replay — nothing is lost.

## Security notes

- Loopback publish + tailnet-only HTTPS (no funnel, nothing public).
- The topic name is random — treat it like a token; rotating = new `EVE_NTFY_TOPIC` +
  re-subscribe on the phone.
- Notifications carry the answer/question headline by design (self-hosted, tailnet-only);
  the one-tap Deny action stays removed (unauthenticated deny path — see approval_push.py).
