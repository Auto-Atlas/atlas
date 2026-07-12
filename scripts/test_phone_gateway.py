# End-to-end check of the phone gateway's WebRTC path: same handshake as
# test_phone_client.py, but driven through phone_gateway.py's proxied endpoints
# (/start + /sessions/{id}/api/offer on 127.0.0.1:8795) instead of straight to
# :8788. Proves the browser page's exact signaling route yields a real media
# session and that Jarvis speaks back over WebRTC. Sends silence up; counts
# audible frames coming down. Override the base with GATEWAY env var.
import asyncio
import json
import os
import urllib.request

from aiortc import RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import AudioStreamTrack

BASE = os.getenv("GATEWAY", "http://127.0.0.1:8795")


async def main():
    pc = RTCPeerConnection()
    pc.addTrack(AudioStreamTrack())
    received = {"frames": 0, "nonsilent": 0}

    @pc.on("track")
    def on_track(track):
        async def drain():
            while True:
                try:
                    frame = await track.recv()
                except Exception:
                    return
                received["frames"] += 1
                try:

                    arr = frame.to_ndarray()
                    if abs(int(arr.astype("int64").max())) > 500:
                        received["nonsilent"] += 1
                except Exception:
                    pass

        asyncio.ensure_future(drain())

    offer = await pc.createOffer()
    await pc.setLocalDescription(offer)

    start_req = urllib.request.Request(
        f"{BASE}/start",
        json.dumps({"transport": "webrtc", "enableDefaultIceServers": True}).encode(),
        {"Content-Type": "application/json"},
    )
    start = json.loads(await asyncio.to_thread(lambda: urllib.request.urlopen(start_req, timeout=30).read()))
    session_id = start["sessionId"]
    print(f"gateway={BASE} session={session_id}")

    req = urllib.request.Request(
        f"{BASE}/sessions/{session_id}/api/offer",
        json.dumps({"sdp": pc.localDescription.sdp, "type": pc.localDescription.type}).encode(),
        {"Content-Type": "application/json"},
    )
    answer = json.loads(await asyncio.to_thread(lambda: urllib.request.urlopen(req, timeout=60).read()))
    await pc.setRemoteDescription(RTCSessionDescription(sdp=answer["sdp"], type=answer["type"]))

    for i in range(30):
        await asyncio.sleep(1)
        if i % 5 == 4:
            print(f"t+{i+1}s state={pc.connectionState} frames={received['frames']} audible={received['nonsilent']}")
        if received["nonsilent"] > 50:
            break

    ok = received["nonsilent"] > 20
    print(
        f"RESULT: state={pc.connectionState} frames={received['frames']} "
        f"audible_frames={received['nonsilent']} -> "
        f"{'PASS (Jarvis spoke through the gateway)' if ok else 'FAIL'}"
    )
    await pc.close()


asyncio.run(main())
