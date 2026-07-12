#!/usr/bin/env python3
"""Run EVE's Hermes A2A adapter (a2a_fabric.build_fabric_app) under uvicorn on loopback.
A SEPARATE process by design (never inside bot.py's event loop) — see
deploy/systemd/atlas-a2a-hermes.service. Requires EVE_A2A_ADAPTER_KEY in the environment/.env:
without it the app fails closed (403s everything), because it fronts `hermes -z`."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def main():
    import uvicorn
    from dotenv import load_dotenv
    load_dotenv()
    if not os.getenv("EVE_A2A_ADAPTER_KEY"):
        print("EVE_A2A_ADAPTER_KEY is not set — the adapter would 403 everything. "
              "Set it in .env first.", file=sys.stderr)
        sys.exit(2)
    import a2a_fabric
    port = int(os.getenv("EVE_A2A_PORT", "8790"))
    uvicorn.run(a2a_fabric.build_fabric_app(), host="127.0.0.1", port=port, log_level="info")


if __name__ == "__main__":
    main()
