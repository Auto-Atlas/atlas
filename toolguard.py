#
# Duplicate tool-call guard, shared by every Jarvis body. Small local models
# emit the SAME tool call several times in parallel within one completion
# (observed on 6/10: ~9x check_inbox in a single turn, and open_on_pc fired
# repeatedly — YouTube actually opened multiple times with nobody asking).
# Each duplicate is a real execution AND each duplicate call-group triggers
# another LLM run, which is where doubled speech came from. While a call is
# in flight (plus a short settle window after it completes), identical
# (function, arguments) calls share ONE execution: the first runs for real;
# the rest await its result and deliver it with run_llm=False so they can't
# spawn extra runs.
#
# The settle window is measured from COMPLETION and kept short on purpose: a
# deliberate "check it again" from the user moments later must re-run for
# real (the old 10s-from-start window answered those with silence). The
# in-flight dupes this guard exists for all arrive inside one completion,
# well within the window. A handler that hangs past HANG_CAP_S stops
# wedging its key — waiters get an honest timeout error and the next call
# runs fresh.
#
# This wrapper is also the honesty net for every tool: a handler that raises
# or returns without delivering a result becomes {"ok": False, ...} instead
# of an exception loose in the pipeline with the LLM never hearing back.
#

import asyncio
import dataclasses
import json
import os
import time

from loguru import logger

from pipecat.frames.frames import FunctionCallResultProperties
from pipecat.services.llm_service import FunctionCallParams

# Seconds after a call COMPLETES during which identical calls still share its
# result. Same-completion dupes land well inside this; a genuine repeat
# request can't (the follow-up LLM run alone takes longer).
DEDUPE_WINDOW_S = float(os.getenv("JARVIS_TOOL_DEDUPE_WINDOW", "2"))
# A call still unfinished after this long no longer blocks its key.
HANG_CAP_S = float(os.getenv("JARVIS_TOOL_HANG_CAP", "300"))


def dedupe(name: str, handler):
    inflight: dict[str, dict] = {}

    async def wrapped(params: FunctionCallParams):
        key = json.dumps(dict(params.arguments or {}), sort_keys=True, default=str)
        logger.info(f"tool call: {name} args={key[:160]}")
        now = time.monotonic()
        for stale in [
            k
            for k, e in inflight.items()
            if (e["fut"].done() and now - e["done_at"] > DEDUPE_WINDOW_S)
            or (not e["fut"].done() and now - e["started"] > HANG_CAP_S)
        ]:
            del inflight[stale]

        entry = inflight.get(key)
        if entry is not None:
            logger.warning(f"duplicate tool call suppressed: {name}({key[:120]})")
            try:
                result = await asyncio.wait_for(asyncio.shield(entry["fut"]), timeout=HANG_CAP_S)
            except asyncio.TimeoutError:
                result = {"ok": False, "error": f"{name} is still running and has not returned"}
            except Exception as e:
                result = {"ok": False, "error": f"original call failed: {e}"}
            await params.result_callback(
                result, properties=FunctionCallResultProperties(run_llm=False)
            )
            return

        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        entry = {"started": now, "done_at": 0.0, "fut": fut}
        inflight[key] = entry
        delivered = False

        def resolve(result):
            # done_at is stamped HERE, synchronously — an add_done_callback
            # fires a tick late, leaving a window where a just-finished entry
            # looks ancient and gets evicted before its dupes arrive.
            if not fut.done():
                fut.set_result(result)
                entry["done_at"] = time.monotonic()

        async def capture(result, **kwargs):
            nonlocal delivered
            delivered = True
            resolve(result)
            await params.result_callback(result, **kwargs)

        fallback = None
        try:
            await handler(dataclasses.replace(params, result_callback=capture))
        except Exception as e:
            logger.warning(f"tool {name} raised: {e!r}")
            fallback = {"ok": False, "error": f"{name} failed: {e}"}
        if fallback is None and not delivered:
            fallback = {"ok": False, "error": f"{name} returned no result"}
        if fallback is not None:
            resolve(fallback)
            if not delivered:
                await params.result_callback(fallback)

    return wrapped
