#!/usr/bin/env python
# LIVE smoke test for the Google Calendar write path — touches the REAL
# calendar of whatever account is connected to the OpenJarvis gcalendar
# connector. Never run by the test suite; run it yourself:
#
#   .venv/bin/python scripts/gcal_write_smoke.py            # status only
#   .venv/bin/python scripts/gcal_write_smoke.py --create   # also creates
#                       "EVE smoke test" tomorrow 09:00 (delete it after)
#
import asyncio
import sys
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from openjarvis_client import OpenJarvisClient  # noqa: E402

import google_calendar_native as gnative  # noqa: E402


async def main() -> int:
    # Preferred: EVE's built-in Google connection (docs/google-calendar.md)
    print(f"native configured:  {gnative.is_configured()} "
          f"(EVE_GOOGLE_CLIENT_ID/SECRET)")
    print(f"native connected:   {gnative.is_connected()} "
          f"({gnative._token_path()})")
    if gnative.is_connected():
        if "--create" not in sys.argv:
            print("\nConnected (native). Re-run with --create to write a test event.")
            return 0
        start = (datetime.now() + timedelta(days=1)).replace(
            hour=9, minute=0, second=0, microsecond=0
        )
        event = await gnative.create_event("EVE smoke test", start, duration_min=15)
        print(f"\nCREATED (native): {event.get('summary')} -> {event.get('htmlLink')}")
        print("Delete it from the calendar when you've verified it.")
        return 0

    print("\nNative path not connected — checking the OpenJarvis connector…")
    client = OpenJarvisClient()
    try:
        detail = await client.connector_detail("gcalendar")
    except Exception as e:
        print(f"FAIL: daemon unreachable at {client.base_url}: {e}")
        return 1
    connected = detail.get("connected")
    setup = detail.get("oauth_setup") or {}
    print(f"daemon:            {client.base_url}")
    print(f"connected:         {connected}")
    print(f"client credentials:{' yes' if setup.get('has_credentials') else ' no'}")
    if not connected:
        print("\nNot connected. Connect first:")
        if setup.get("has_credentials"):
            print(f"  open {client.base_url}/v1/connectors/gcalendar/oauth/start")
        else:
            print("  follow docs/google-calendar.md (one-time OAuth client setup)")
        return 1
    if "--create" not in sys.argv:
        print("\nConnected. Re-run with --create to write a test event.")
        return 0
    start = (datetime.now() + timedelta(days=1)).replace(
        hour=9, minute=0, second=0, microsecond=0
    )
    event = await client.gcalendar_create_event(
        "EVE smoke test", f"{start:%Y-%m-%d %H:%M}", duration_min=15
    )
    print(f"\nCREATED: {event.get('summary')} -> {event.get('htmlLink')}")
    print("Delete it from the calendar when you've verified it.")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
