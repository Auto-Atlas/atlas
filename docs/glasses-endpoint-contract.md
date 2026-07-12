# EVE Glasses Endpoint Contract

The wire contract for **any** smart-glasses bridge that wants to give EVE eyes — a
MentraOS app, a Meta companion track, or anything else that can hold a WebSocket and
POST a JPEG. There are **zero server changes** required to add a bridge: the same
approval API the phone app uses is source-agnostic. This document is the source of
truth; the env knobs it lists are read in one place, [`glasses_link.py`](../glasses_link.py).

There are two independent legs:

1. **On-demand `look`** — EVE asks for a single frame when the user says "look at
   this / what am I looking at". Answered over the live WebSocket + one HTTP POST.
2. **Continuous vision** — the glasses push their point-of-view as an RTMP stream;
   [`glasses_stream.py`](../glasses_stream.py) samples it, describes each frame on the
   local VLM, and narrates a throttled line back into the wearer's ear.

A bridge can implement either leg or both.

---

## Auth

Every REST call uses `Authorization: Bearer <token>`. The WebSocket carries the same
token in the `Sec-WebSocket-Protocol` header as `bearer, <token>` (a token in the URL
query string leaks into logs and proxies — never put it there).

The token is the EVE app token (`EVE_APP_TOKEN`, or the contents of
`approval_token.txt`). The base URL is the approval API, exposed on the tailnet via
`tailscale serve --https=8443 http://127.0.0.1:8799` (any free HTTPS port works -- if :8443 is taken by the typed web UI, use e.g. `--https=8446`).

---

## Leg 1 — On-demand `look`

### 1. Connect the live stream as a glasses surface

```
GET  wss://<host>:8443/v1/stream?surface=glasses
Sec-WebSocket-Protocol: bearer, <token>
```

- `surface` is a **routing label, not a secret**. Valid values: `phone`, `glasses`.
  Omitting it defaults to `phone` (that's what the existing app does). An unknown value
  closes the socket with code **4400**; a bad/missing token closes with **4401**.
- The server accepts with subprotocol `bearer`. After the handshake the server only
  **pushes** events; send periodic text frames as keepalive.

wscat example:

```
wscat -c "wss://<host>:8443/v1/stream?surface=glasses" \
      -s "bearer" -s "<token>"
```

### 2. Receive `capture_frame` events

```json
{ "type": "capture_frame", "request_id": "3f9c...", "prompt": "what plant is this", "source": "glasses" }
```

**Source filtering rule (required):** honor `source`.

- `source == "any"` → any surface may answer.
- `source == "glasses"` → only glasses clients answer.
- `source == "phone"` → **ignore the event** (it is aimed at the phone).

`request_id` is plain lowercase hex, 8–32 chars. `prompt` is the user's question (may
be empty). You will also receive `surface_visual` events (below) and live
tool/delegation events — ignore any `type` you don't handle.

### 3. Answer with a frame

Snap a JPEG and POST it:

```
POST /v1/vision/frame
Authorization: Bearer <token>
Content-Type: application/json

{ "request_id": "3f9c...", "jpeg_b64": "<base64 JPEG>" }
```

- `request_id` must echo the one from `capture_frame` and be plain lowercase hex
  (**400** otherwise).
- `jpeg_b64` must be valid base64 of a non-empty JPEG, **≤ 8 MB** decoded (**413** if
  larger, **400** if empty/invalid base64).
- The frame is transient: EVE's tool reads it **once** from the spool and deletes it.
  It never leaves the box.

curl example:

```
curl -sS https://<host>:8443/v1/vision/frame \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"request_id\":\"3f9c...\",\"jpeg_b64\":\"$(base64 -w0 frame.jpg)\"}"
```

### `surface_visual` event shape (verbatim)

EVE pushes cards to display over the same stream. Shape as broadcast by the approval API:

```json
{
  "type": "surface_visual",
  "kind": "<=40 chars",
  "title": "<=120 chars",
  "visual_id": "plain lowercase hex or ''",
  "url": "<=200 chars",
  "text": "<=4000 chars"
}
```

Fetch the image (when `visual_id` is set) with
`GET /v1/visual/<visual_id>` (Bearer auth) → `image/jpeg`. Reads are non-consuming.

---

## Leg 2 — Continuous vision (RTMP)

Publish an H.264/FLV RTMP stream to the target below; `glasses_stream.py` runs the
listener (`ffmpeg -listen 1`), samples at `EVE_GLASSES_SAMPLE_FPS`, describes each
latest frame on the local VLM, and delivers narration.

**Push target:**

```
rtmp://<host>:<EVE_GLASSES_RTMP_PORT>/<EVE_GLASSES_RTMP_APP>
# default: rtmp://<host>:1935/eve
```

Test-publish a synthetic stream:

```
ffmpeg -re -f lavfi -i testsrc=size=640x480:rate=15 \
       -c:v flv -f flv rtmp://<host>:1935/eve
```

Only the **latest** decoded frame is ever processed — a slow VLM never builds a
backlog. When the stream ends, the service loops back to listening.

### Narration webhook

When `EVE_GLASSES_NARRATE_URL` is set, each spoken line is POSTed to the bridge, which
plays it in the wearer's ear:

```
POST <EVE_GLASSES_NARRATE_URL>
Content-Type: application/json

{ "text": "You're looking at a workbench with a red toolbox." }
```

Return 2xx. Narration is throttled: never more often than `EVE_GLASSES_NARRATE_MIN_S`,
and near-identical descriptions are suppressed. If the URL is unset, EVE runs
log-only (useful for bring-up).

---

## Env knobs (all with product-safe defaults)

Read centrally in [`glasses_link.py`](../glasses_link.py):

| Env var | Default | Meaning |
| --- | --- | --- |
| `EVE_GLASSES_ENABLED` | `0` (off) | Master gate for the continuous-vision service. |
| `EVE_GLASSES_RTMP_PORT` | `1935` | RTMP listener port. |
| `EVE_GLASSES_RTMP_APP` | `eve` | RTMP application / stream-key path. |
| `EVE_GLASSES_SAMPLE_FPS` | `1.0` | Frames/sec pulled off the stream for the VLM. |
| `EVE_GLASSES_NARRATE_MIN_S` | `8.0` | Floor between spoken narrations. |
| `EVE_GLASSES_NARRATE_URL` | `""` | Bridge webhook for ear delivery; empty = log-only. |
| `EVE_VLM_URL` | `http://127.0.0.1:8093` | Local vision model (shared with on-demand vision). |
| `EVE_VLM_MODEL` | `qwen3-vl` | VLM model id. |

Additional service knobs: `EVE_GLASSES_PROMPT` (the continuous-mode prompt),
`EVE_FFMPEG_BIN` (default `/usr/bin/ffmpeg`), `EVE_VLM_TIMEOUT_S` (default `90`).

Deploy template: [`deploy/eve-glasses-stream.service`](../deploy/eve-glasses-stream.service)
(not installed automatically).
