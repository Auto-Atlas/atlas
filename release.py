# release.py
#
# Headless release of the REAL tool handlers (spec §1.5). When the owner approves a staged
# draft from the app, approval_api fires the frozen draft by running the SAME handler the
# voice loop would run (handle_create_invoice / handle_send_to_channel) — but OUTSIDE any
# voice context. Those handlers only ever read `params.arguments` and await
# `params.result_callback(result)`, and they make their own outbound HTTP, so they run
# headless cleanly. This mirrors tool_policy._safe_release's exception->result safety net.
#
# Import invariant (BMAD): this module must NOT import jarvis_core / bot / phone_bot /
# speaker_state (those carry the phone single-instance socket lock, load_skills() at import,
# and process-global voice state). Importing the tool modules transitively pulls in pipecat,
# which is fine — pipecat is an installed dependency.
#
from loguru import logger

import channel_tool
import invoice_tool


class HeadlessFunctionCallParams:
    """Duck-typed stand-in for pipecat's FunctionCallParams for the no-voice-context release
    path. The real handlers touch ONLY .arguments and .result_callback; any other attribute
    access is a bug (a tool that needs the live voice context must not be remotely released),
    so __getattr__ raises loudly rather than silently returning None."""

    def __init__(self, arguments: dict):
        self._arguments = arguments
        self.result: dict | None = None

    @property
    def arguments(self) -> dict:
        return self._arguments

    async def result_callback(self, result, **kwargs):
        self.result = result

    def __getattr__(self, name):
        # __getattr__ is only called for attributes NOT found normally, so _arguments/
        # result/arguments/result_callback never reach here.
        raise AttributeError(
            f"headless release accessed params.{name!r}; only .arguments and "
            f".result_callback are supported. A tool that needs the live voice context "
            f"cannot be released remotely."
        )


# The single registry of tools approvable from the app. Built by referencing the handler
# functions directly (module-qualified) so the import boundary stays inspectable.
RELEASABLE_HANDLERS: dict = {
    "create_invoice": invoice_tool.handle_create_invoice,
    "send_to_channel": channel_tool.handle_send_to_channel,
}


def register_releasable(name: str, handler) -> None:
    """Register a releasable handler. Used in tests to drive the real store+release
    machinery with a real side-effecting handler standing in for an upstream tool."""
    RELEASABLE_HANDLERS[name] = handler


async def release(tool: str, args: dict) -> dict:
    """Run the real handler for `tool` with the frozen `args`, headless. Returns the real
    result dict (honest error dict if the tool is unknown, the handler raised, or the
    upstream was unreachable). Never fabricates success."""
    handler = RELEASABLE_HANDLERS.get(tool)
    if handler is None:
        return {"ok": False, "error": f"no releasable handler for {tool!r}"}
    params = HeadlessFunctionCallParams(args)
    try:
        await handler(params)
    except Exception as e:  # mirror tool_policy._safe_release's net
        logger.warning(f"release: {tool} raised: {e!r}")
        return {"ok": False, "error": f"{tool} failed: {e!r}"}
    if params.result is None:
        return {"ok": False, "error": f"{tool} returned no result"}
    return params.result
