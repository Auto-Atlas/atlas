# EVE customer transport — WireGuard (the sellable path)

Requirement: the apps must be configurable over WireGuard so a customer can use
an EVE server we sell/host for them. Tailscale is a personal transport, never a
product assumption.

## The one product rule

Every EVE client (Android, Wear OS, iOS, watchOS, desktop) talks **HTTPS to a
configurable base URL + pairing token**. Nothing in any app may assume tailnet,
LAN, or any specific tunnel. If the app works against `https://<anything>` with
a valid token, every topology below works without app changes.

## Topology A — hosted relay (default for sold customers)

```
Customer app → https://<customer>.eve.example (VPS, TLS, static IP)
             → WireGuard tunnel → customer's EVE server (behind CGNAT/Starlink/LAN)
```

- The app needs NO VPN at all — plain HTTPS to a per-customer domain. Zero
  client friction; works on watches (they just ride the phone gateway anyway).
- Canonical VPS recipe: `~/.claude/commands/setup-wireguard.md` (the
  `/setup-wireguard` command) — WG on the VPS (51820/udp), peer per EVE server,
  nginx/caddy TLS on 443 proxying to the WG-internal address.
- Per-customer provisioning: subdomain A record → VPS; WG peer (`wg genkey`,
  AllowedIPs `<wg-ip>/32`); EVE pairing token (the `approval_token.txt`
  pattern — per-install, revocable). Revocation = drop the peer + revoke the
  token; both are one-liners, document them per customer.

## Topology B — direct WireGuard peer (privacy-maximal customers)

```
Customer device (WG tunnel) → wg-internal address of the EVE server → HTTPS
```

- v1: the customer imports a WG profile we issue (QR code / `.conf` file) into
  the standard WireGuard app (Android/iOS/macOS/Windows all have one), then
  points the EVE app at the WG-internal base URL. No app code needed beyond the
  base-URL setting that already exists.
- v2 (flag-gated app increment, only if customers ask): embedded tunnel —
  Android `com.wireguard.android:tunnel` (VpnService), iOS/watchOS-host
  `WireGuardKit` (NetworkExtension / NEPacketTunnelProvider). Import the same
  QR/`.conf`. Watches never embed WG: the phone is the gateway (established
  design in both watch goals).

## What this means per client (mirrored into the goal docs)

- **Pairing UI**: base URL is a free-form HTTPS field + QR import; presets are
  labels, not assumptions ("Demo", "My server", "Hosted"). Token in
  Keychain/EncryptedSharedPreferences.
- **Cert reality**: Topology A gives real TLS via the domain. Topology B v1 is
  HTTP inside the tunnel or self-signed — clients must support a per-server
  CA/self-signed pin (explicit, user-approved, shown as such) rather than
  demanding public CAs.
- **No transport telemetry**: the app never phones home about which topology a
  customer uses.
- **Desktop CSP**: the Tauri app's default CSP allows loopback + `*.ts.net`
  origins only (exfiltration hardening — security review 2026-07-03). A
  Topology-A relay build must add the customer relay domain to `connect-src`
  at build time (edit/overlay `src-tauri/tauri.conf.json` in the customer
  build); do NOT ship a wildcard `https:` CSP.

## Sales/ops checklist (per customer)

1. Subdomain + A record → relay VPS (or hand them a WG profile for Topology B).
2. VPS: add WG peer for their EVE server; TLS vhost → their WG IP.
3. EVE server: pairing token minted; `.env` `EVE_PUBLIC_BASE_URL` set.
4. Hand-off: QR (base URL + token) for the app; WG `.conf`/QR if Topology B.
5. Record revocation steps in the customer sheet.

This doc covers the connectivity leg of the productization plan (installer /
licensing / eve doctor).
