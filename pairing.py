# pairing.py
#
# Phone pairing for the EVE app: build the connection payload, render it as a QR PNG, and show
# it on EVE's screen. The phone scans the QR to receive {base URL, app token} — no hardcoded
# credentials in the app, no hand-typing a token.
#
# Self-contained: stdlib + qrcode only. Never imports the voice runtime or FastAPI.
#
import os
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlencode

import qrcode

_SCHEME = "eve://connect"


def app_token() -> str:
    """The app bearer token, resolved the same way approval_api does: EVE_APP_TOKEN env, else the
    contents of approval_token.txt next to this file. Empty string if neither is set."""
    token = os.getenv("EVE_APP_TOKEN", "").strip()
    if token:
        return token
    token_file = Path(os.getenv("EVE_APP_TOKEN_FILE", str(Path(__file__).parent / "approval_token.txt")))
    if token_file.is_file():
        return token_file.read_text(encoding="utf-8").strip()
    return ""


def base_url() -> str:
    """The public tailnet base URL the phone should hit (EVE_APP_BASE_URL in .env)."""
    return os.getenv("EVE_APP_BASE_URL", "").strip()


def build_pairing_uri(base: str, token: str) -> str:
    """The QR payload: a deep link the app parses into a connection. Pure + url-safe so the
    base URL's `://` and `:port` survive transport."""
    return f"{_SCHEME}?" + urlencode({"base": base, "token": token})


def render_qr_png(uri: str, path: str | None = None) -> str:
    """Render the pairing URI to a QR PNG; returns the file path."""
    if path is None:
        path = str(Path(tempfile.gettempdir()) / "eve-pairing-qr.png")
    img = qrcode.make(uri)
    img.save(path)
    return path


def display_qr(path: str) -> bool:
    """Open the QR image on EVE's screen (DISPLAY is set by the systemd unit). Non-blocking;
    returns True if a viewer was launched. Tries eog, then xdg-open."""
    for viewer in ("eog", "xdg-open"):
        if _which(viewer):
            try:
                subprocess.Popen(
                    [viewer, path],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
                return True
            except Exception:
                continue
    return False


def _which(cmd: str) -> bool:
    from shutil import which
    return which(cmd) is not None


def show_pairing_qr() -> dict:
    """Build + render + display the pairing QR. Returns a small status dict for the handler.
    Never raises into the caller — a missing token/url is reported, not thrown."""
    base, token = base_url(), app_token()
    if not base:
        return {"ok": False, "error": "EVE_APP_BASE_URL is not set"}
    if not token:
        return {"ok": False, "error": "no app token found (approval_token.txt / EVE_APP_TOKEN)"}
    uri = build_pairing_uri(base, token)
    path = render_qr_png(uri)
    shown = display_qr(path)
    return {"ok": True, "displayed": shown, "path": path, "base": base}
