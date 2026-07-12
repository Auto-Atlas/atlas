# glasses_link.py — the ONE place that reads every glasses knob from the environment.
#
# EVE's glasses story has two legs that meet here:
#   1. On-demand "look" — a glasses bridge (MentraOS app, or a Meta companion track)
#      connects the same WS /v1/stream?surface=glasses the phone app uses, answers a
#      capture_frame with POST /v1/vision/frame, and EVE describes it (vision_tool).
#   2. Continuous vision — glasses_stream.py runs an RTMP listener, samples the wearer's
#      point-of-view at a low FPS, describes each frame on the LOCAL VLM, and (throttled)
#      narrates into the wearer's ear via the bridge's narrate webhook.
#
# This module is stdlib-only config: it does NOT open sockets or import the service. It is
# the anchor the endpoint contract (docs/glasses-endpoint-contract.md) points at, so the
# documented env names and the code that reads them can never drift apart. Every value is
# an env var with a sane default — EVE is a product, nothing owner-specific is baked in.
#
import os
from dataclasses import dataclass


def _env_str(name: str, default: str) -> str:
    v = os.getenv(name)
    return default if v is None else v


def _env_float(name: str, default: float) -> float:
    """Parse a float env; fall back to the default on blank/garbage (never crash the
    service over a fat-fingered knob)."""
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class GlassesConfig:
    enabled: bool
    rtmp_port: int
    rtmp_app: str
    sample_fps: float
    narrate_min_s: float
    narrate_url: str          # empty => log-only (no ear delivery)
    vlm_url: str
    vlm_model: str

    @property
    def rtmp_url(self) -> str:
        """The RTMP push target a glasses continuous-mode source publishes to.
        0.0.0.0 = listen on all interfaces (the bridge reaches us over the tailnet)."""
        return f"rtmp://0.0.0.0:{self.rtmp_port}/{self.rtmp_app}"


def load() -> GlassesConfig:
    """Read every glasses knob from the environment, with product-safe defaults.

    EVE_GLASSES_ENABLED    (default off)  master gate for continuous mode
    EVE_GLASSES_RTMP_PORT  (1935)         RTMP listener port
    EVE_GLASSES_RTMP_APP   ("eve")        RTMP application/stream-key path
    EVE_GLASSES_SAMPLE_FPS (1.0)          frames/sec pulled off the stream for the VLM
    EVE_GLASSES_NARRATE_MIN_S (8.0)       floor between spoken narrations
    EVE_GLASSES_NARRATE_URL (empty)       bridge webhook that plays text in the ear; empty = log-only
    EVE_VLM_URL / EVE_VLM_MODEL           reuse the existing on-demand-vision names
    """
    return GlassesConfig(
        enabled=_env_bool("EVE_GLASSES_ENABLED", False),
        rtmp_port=_env_int("EVE_GLASSES_RTMP_PORT", 1935),
        rtmp_app=_env_str("EVE_GLASSES_RTMP_APP", "eve"),
        sample_fps=_env_float("EVE_GLASSES_SAMPLE_FPS", 1.0),
        narrate_min_s=_env_float("EVE_GLASSES_NARRATE_MIN_S", 8.0),
        narrate_url=_env_str("EVE_GLASSES_NARRATE_URL", "").strip(),
        # Reuse the SAME names on-demand vision uses so one VLM config serves both legs.
        vlm_url=_env_str("EVE_VLM_URL", "http://127.0.0.1:8093").rstrip("/"),
        vlm_model=_env_str("EVE_VLM_MODEL", "qwen3-vl"),
    )
