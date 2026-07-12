"""Proactive wake scheduler — the server-side heartbeat that fires the morning ritual.

Ticks once a minute and sends an FCM wake to every registered device whose OWN local
wake time is now. This is the durable, multi-tenant answer to "wake me even if the app
isn't running": the wake is server-initiated, so it doesn't depend on each phone's local
alarm surviving a sleep/kill. (A deliberate force-stop is the one thing nothing remote can
beat — but battery-exemption handles that case.)

Run standalone:  python push_scheduler.py   (also launched by start-eve.ps1)
"""

from __future__ import annotations

import asyncio
import time

from loguru import logger

import push_sender


async def main():
    logger.info("EVE wake scheduler started — ticking every minute")
    while True:
        try:
            results = push_sender.wake_due()
            if results:
                logger.info(f"fired {len(results)} wake push(es): {results}")
        except Exception as e:
            logger.warning(f"wake scheduler tick error: {e}")
        # align to the top of the next minute so wake times land on the minute
        await asyncio.sleep(max(1.0, 60 - (time.time() % 60)))


if __name__ == "__main__":
    asyncio.run(main())
