"""Smoke test: prove the open-source LiveKit path works end-to-end at the
connection layer — our self-generated token + the local dev server + a real
join. No account, no card, no paid key. Uses the same `livekit.rtc` SDK that
pipecat's LiveKitTransport uses under the hood, so a success here means the
transport swap in phone_bot will connect too.

Run:  .venv/Scripts/python.exe livekit_smoke.py   (with the dev server up)
"""

import asyncio

import livekit_rooms
from livekit import rtc


async def main() -> None:
    call = livekit_rooms.new_call()
    print(f"server={call['ws_url']} room={call['room']}")

    room = rtc.Room()
    joined = asyncio.Event()

    @room.on("connected")
    def _on_connected() -> None:
        joined.set()

    await room.connect(call["ws_url"], call["bot_token"])
    # connect() resolves once connected; the event is belt-and-suspenders.
    print(f"CONNECTED  identity={room.local_participant.identity!r}  room={room.name!r}")
    await asyncio.sleep(2)
    await room.disconnect()
    print("disconnected OK — open-source LiveKit join verified")


if __name__ == "__main__":
    asyncio.run(main())
