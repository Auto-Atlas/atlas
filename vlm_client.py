# vlm_client.py — the ONE call into the local vision model.
#
# Both vision legs hand a JPEG to the same on-box VLM (eve-vlm.service — llama-server
# qwen3-vl on :8093, OpenAI-compatible chat/completions with an inline image):
#   - vision_tool.look   : on-demand, one frame per user request
#   - glasses_stream.py  : continuous, the latest sampled frame off an RTMP feed
# Factoring the request shape here means the payload/timeout/error handling lives in one
# place and can never drift between the two callers. The frame never leaves the box.
#
import base64
import os

import aiohttp


def _default_vlm_base() -> str:
    return os.getenv("EVE_VLM_URL", "http://127.0.0.1:8093").rstrip("/")


def _default_model() -> str:
    return os.getenv("EVE_VLM_MODEL", "qwen3-vl")


def _default_timeout_s() -> float:
    # Vulkan VLM on a shared GPU: first token can take a while — generous total.
    return float(os.getenv("EVE_VLM_TIMEOUT_S", "90"))


async def describe(
    jpeg: bytes,
    prompt: str,
    *,
    vlm_url: str | None = None,
    model: str | None = None,
    timeout_s: float | None = None,
    max_tokens: int = 350,
) -> str:
    """One chat completion against the local VLM with the frame inlined. Raises
    RuntimeError with an actionable message on any non-200 / empty result so the
    caller can name the vision-model leg specifically (failure honesty)."""
    b64 = base64.b64encode(jpeg).decode()
    payload = {
        "model": model or _default_model(),
        "max_tokens": max_tokens,
        "temperature": 0.2,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image_url",
                 "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
                {"type": "text",
                 "text": prompt or "Describe what you see, briefly and concretely."},
            ],
        }],
    }
    base = (vlm_url or _default_vlm_base()).rstrip("/")
    timeout = aiohttp.ClientTimeout(total=timeout_s if timeout_s is not None else _default_timeout_s())
    async with aiohttp.ClientSession(timeout=timeout) as s:
        async with s.post(f"{base}/v1/chat/completions", json=payload) as r:
            data = await r.json(content_type=None)
            if r.status != 200:
                err = (data or {}).get("error", {})
                raise RuntimeError(f"vision model error: {err.get('message', f'HTTP {r.status}')}")
    text = (data.get("choices") or [{}])[0].get("message", {}).get("content", "")
    if not str(text).strip():
        raise RuntimeError("vision model returned an empty description")
    return str(text).strip()
