# Synthetic phone: a real WebRTC peer that connects to phone_bot.py exactly
# like the phone browser will — SDP offer to /api/offer, silent mic track up,
# and counts the REAL audio frames coming back (the greeting TTS). Proves the
# entire path: handshake -> pipeline boot -> qwen3 -> Kokoro -> WebRTC audio.
import asyncio
import json
import urllib.request

from aiortc import RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import AudioStreamTrack  # silence generator


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

    # New runner protocol: /start registers a session, then the SDP offer goes
    # to /sessions/{sessionId}/api/offer — same flow the prebuilt UI uses.
    start_req = urllib.request.Request(
        "http://127.0.0.1:8788/start",
        json.dumps({"transport": "webrtc", "enableDefaultIceServers": True}).encode(),
        {"Content-Type": "application/json"},
    )
    start = json.loads(await asyncio.to_thread(lambda: urllib.request.urlopen(start_req, timeout=30).read()))
    session_id = start["sessionId"]
    print(f"session: {session_id}")

    req = urllib.request.Request(
        f"http://127.0.0.1:8788/sessions/{session_id}/api/offer",
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

    print(
        f"RESULT: state={pc.connectionState} frames={received['frames']} audible_frames={received['nonsilent']} "
        f"-> {'PASS (Jarvis spoke over WebRTC)' if received['nonsilent'] > 20 else 'FAIL'}"
    )
    await pc.close()


asyncio.run(main())
