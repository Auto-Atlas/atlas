#
# google_calendar_native — EVE's OWN "Connect Google Calendar", no OpenJarvis needed.
#
# This is the self-contained path a customer who only deploys jarvis-sidecar can
# use: put an OAuth client id/secret in .env (their own Google Cloud / Firebase
# project — docs/google-calendar.md), say "EVE, connect my calendar", approve
# Google's consent screen once in the browser, done. Zero new dependencies —
# the OAuth dance is a tiny stdlib loopback server + urllib token exchange, the
# Calendar API calls ride the aiohttp the sidecar already has.
#
# Scope is the NARROWEST that writes: calendar.events (read/write events only,
# no calendar management). Tokens live in a chmod-600 JSON file (default
# ~/.eve/google_calendar_token.json), refreshed automatically on 401, revoked
# on disconnect. The client secret + tokens never leave the box.
#
# .env: EVE_GOOGLE_CLIENT_ID / EVE_GOOGLE_CLIENT_SECRET  (the OAuth client)
#       EVE_GOOGLE_TOKEN_PATH   (optional, default ~/.eve/google_calendar_token.json)
#       EVE_GOOGLE_OAUTH_PORT   (optional, default 8792 — 8789/8790/8791 are taken by OpenJarvis/Hermes/the sidecar)
#

import json
import os
import threading
import webbrowser
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import urlencode

import aiohttp
from loguru import logger

_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
_REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
_API_BASE = "https://www.googleapis.com/calendar/v3"
_SCOPES = ["https://www.googleapis.com/auth/calendar.events"]
_TIMEOUT = aiohttp.ClientTimeout(total=15)


def _client_creds():
    return (os.getenv("EVE_GOOGLE_CLIENT_ID", "").strip(),
            os.getenv("EVE_GOOGLE_CLIENT_SECRET", "").strip())


def _token_path() -> Path:
    return Path(os.getenv("EVE_GOOGLE_TOKEN_PATH",
                          str(Path.home() / ".eve" / "google_calendar_token.json")))


def _oauth_port() -> int:
    try:
        return int(os.getenv("EVE_GOOGLE_OAUTH_PORT", "8792"))
    except ValueError:
        return 8792


def _redirect_uri() -> str:
    return f"http://127.0.0.1:{_oauth_port()}/oauth/callback"


def is_configured() -> bool:
    """True when an OAuth client id + secret are in the environment."""
    cid, secret = _client_creds()
    return bool(cid and secret)


def load_tokens():
    p = _token_path()
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def save_tokens(tokens) -> None:
    p = _token_path()
    p.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    p.write_text(json.dumps(tokens, indent=2), encoding="utf-8")
    os.chmod(p, 0o600)


def is_connected() -> bool:
    """True when a refresh token is on disk — the durable credential."""
    tokens = load_tokens()
    return bool(tokens and tokens.get("refresh_token"))


def auth_url(state: str = "", code_challenge: str = "") -> str:
    cid, _ = _client_creds()
    params = {
        "client_id": cid,
        "redirect_uri": _redirect_uri(),
        "response_type": "code",
        "scope": " ".join(_SCOPES),
        "access_type": "offline",   # -> refresh_token
        "prompt": "consent",        # re-consent always re-issues the refresh_token
    }
    if state:
        params["state"] = state
    if code_challenge:
        params["code_challenge"] = code_challenge
        params["code_challenge_method"] = "S256"
    return f"{_AUTH_ENDPOINT}?{urlencode(params)}"


def _make_pkce() -> tuple:
    """RFC 7636 verifier + S256 challenge (standard for native/loopback apps)."""
    import base64
    import hashlib
    import secrets

    verifier = secrets.token_urlsafe(64)[:128]
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return verifier, challenge


def _parse_callback(path: str, expected_state: str):
    """Validate a loopback callback request path -> (code, error_message).

    Rejects any callback whose ``state`` doesn't match the one this flow
    minted — a forged/replayed hit on the loopback port (login-CSRF) can't
    plant an attacker's authorization code.
    """
    import secrets
    from urllib.parse import parse_qs, urlparse

    params = parse_qs(urlparse(path).query)
    got_state = params.get("state", [""])[0]
    if not got_state or not secrets.compare_digest(got_state, expected_state):
        return None, "state mismatch (CSRF guard)"
    if "error" in params:
        return None, params["error"][0]
    code = params.get("code", [""])[0]
    if not code:
        return None, "missing authorization code"
    return code, None


def _exchange_code_sync(code: str, code_verifier: str = "") -> dict:
    """Blocking auth-code -> tokens exchange (runs in the connect thread)."""
    import urllib.request

    cid, secret = _client_creds()
    fields = {
        "code": code, "client_id": cid, "client_secret": secret,
        "redirect_uri": _redirect_uri(), "grant_type": "authorization_code",
    }
    if code_verifier:
        fields["code_verifier"] = code_verifier
    data = urlencode(fields).encode()
    req = urllib.request.Request(_TOKEN_ENDPOINT, data=data, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def start_connect_flow() -> str:
    """Kick off the loopback OAuth flow in a background thread; return the consent URL.

    The voice loop must not block for the user's browser click, so the
    one-shot callback server + token exchange run on a daemon thread. Callers
    speak/show the returned URL; is_connected() flips once consent lands.
    """
    import secrets

    state = secrets.token_urlsafe(32)
    verifier, challenge = _make_pkce()
    url = auth_url(state=state, code_challenge=challenge)

    def _run() -> None:
        from http.server import BaseHTTPRequestHandler, HTTPServer

        code_box, err_box = [], []

        class _Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 — required override name
                code, err = _parse_callback(self.path, state)
                if code:
                    code_box.append(code)
                else:
                    # A CSRF-rejected hit doesn't end the wait — the genuine
                    # redirect may still arrive; real provider errors do end it.
                    if err != "state mismatch (CSRF guard)":
                        err_box.append(err)
                    logger.warning(f"google_calendar_native: callback rejected: {err}")
                self.send_response(200 if code else 400)
                self.send_header("Content-Type", "text/html")
                self.end_headers()
                from persona import ASSISTANT_NAME  # configured self-name; EVE is a product
                body = (f"<h2>Calendar connected!</h2><p>You can close this tab "
                        f"and go back to {ASSISTANT_NAME}.</p>".encode() if code else
                        f"<h2>Authorization failed</h2><p>Ask {ASSISTANT_NAME} to try again.</p>".encode())
                self.wfile.write(b"<html><body style='font-family:system-ui;"
                                 b"text-align:center;padding:60px'>" + body +
                                 b"</body></html>")

            def log_message(self, *_):
                pass

        try:
            server = HTTPServer(("127.0.0.1", _oauth_port()), _Handler)
        except OSError as e:
            logger.warning(f"google_calendar_native: callback port busy: {e}")
            return
        import time

        server.timeout = 30
        deadline = time.time() + 300  # 5 min for the human to click Allow
        while not code_box and not err_box and time.time() < deadline:
            server.handle_request()
        server.server_close()
        if not code_box:
            logger.warning(f"google_calendar_native: consent denied/failed: {err_box}")
            return
        try:
            tokens = _exchange_code_sync(code_box[0], code_verifier=verifier)
        except Exception as e:
            logger.warning(f"google_calendar_native: token exchange failed: {e}")
            return
        if not tokens.get("refresh_token"):
            logger.warning("google_calendar_native: no refresh_token in response")
        save_tokens(tokens)
        logger.info("google_calendar_native: connected (tokens stored, mode 600)")

    threading.Thread(target=_run, daemon=True).start()
    try:
        webbrowser.open(url)
    except Exception:
        pass  # headless box — the caller still speaks/shows the URL
    return url


async def disconnect() -> None:
    """Best-effort Google-side revoke, then delete the token file."""
    tokens = load_tokens() or {}
    tok = tokens.get("refresh_token") or tokens.get("access_token")
    if tok:
        try:
            async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
                async with session.post(_REVOKE_ENDPOINT, data={"token": tok}):
                    pass
        except Exception as e:
            logger.warning(f"google_calendar_native: revoke failed (deleting anyway): {e}")
    p = _token_path()
    if p.exists():
        p.unlink()


async def _refresh_access_token(session) -> str:
    tokens = load_tokens()
    cid, secret = _client_creds()
    if not (tokens and tokens.get("refresh_token") and cid and secret):
        raise RuntimeError("Google Calendar is not connected — run connect first.")
    async with session.post(_TOKEN_ENDPOINT, data={
        "client_id": cid, "client_secret": secret,
        "refresh_token": tokens["refresh_token"], "grant_type": "refresh_token",
    }) as resp:
        body = await resp.json(content_type=None)
        if resp.status != 200 or not body.get("access_token"):
            raise RuntimeError(
                f"Google token refresh failed (HTTP {resp.status}) — "
                "the connection may have been revoked; reconnect the calendar.")
    tokens["access_token"] = body["access_token"]
    save_tokens(tokens)
    return body["access_token"]


def _event_body(title, start: datetime, duration_min: int, all_day: bool) -> dict:
    body = {"summary": title}
    if all_day:
        # Google's all-day end date is EXCLUSIVE.
        body["start"] = {"date": f"{start:%Y-%m-%d}"}
        body["end"] = {"date": f"{start + timedelta(days=1):%Y-%m-%d}"}
    else:
        end = start + timedelta(minutes=duration_min)
        # Naive local -> offset-aware RFC3339 so Google can't guess wrong.
        body["start"] = {"dateTime": start.astimezone().isoformat()}
        body["end"] = {"dateTime": end.astimezone().isoformat()}
    return body


async def _authed_json(session, method, url, *, json_body=None, params=None) -> dict:
    """One authenticated Calendar API call with a one-shot 401 refresh-and-retry."""
    token = (load_tokens() or {}).get("access_token", "")
    for attempt in (1, 2):
        async with session.request(
            method, url, json=json_body, params=params,
            headers={"Authorization": f"Bearer {token}"},
        ) as resp:
            if resp.status == 401 and attempt == 1:
                token = await _refresh_access_token(session)
                continue
            data = await resp.json(content_type=None)
            if resp.status not in (200, 201):
                msg = (data or {}).get("error", {}).get("message", f"HTTP {resp.status}")
                raise RuntimeError(f"Google Calendar API error: {msg}")
            return data
    raise RuntimeError("unreachable")  # loop always returns or raises


async def create_event(title, start: datetime, duration_min=60, all_day=False,
                       calendar_id="primary") -> dict:
    """events.insert with a one-shot 401 refresh-and-retry. Raises on failure."""
    body = _event_body(title, start, duration_min, all_day)
    url = f"{_API_BASE}/calendars/{calendar_id}/events"
    async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
        try:
            data = await _authed_json(session, "POST", url, json_body=body)
        except RuntimeError as e:
            raise RuntimeError(str(e).replace("API error", "rejected the event"))
        return {
            "id": data.get("id", ""),
            "htmlLink": data.get("htmlLink", ""),
            "summary": data.get("summary", title),
        }


async def delete_event(event_id: str, calendar_id: str = "primary") -> bool:
    """events.delete with the same one-shot 401 refresh-and-retry. Returns True if
    the event is gone (deleted now, or already deleted/absent — 404/410 count as
    gone: the caller's goal is 'not on the calendar', not 'I did the deleting').
    Raises on real API rejections. Can't reuse _authed_json: DELETE succeeds with
    204 and an EMPTY body, which its JSON parse would choke on."""
    url = f"{_API_BASE}/calendars/{calendar_id}/events/{event_id}"
    async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
        token = (load_tokens() or {}).get("access_token", "")
        for attempt in (1, 2):
            async with session.request(
                "DELETE", url, headers={"Authorization": f"Bearer {token}"},
            ) as resp:
                if resp.status == 401 and attempt == 1:
                    token = await _refresh_access_token(session)
                    continue
                if resp.status in (200, 204, 404, 410):
                    return True
                data = await resp.json(content_type=None)
                msg = (data or {}).get("error", {}).get("message", f"HTTP {resp.status}")
                raise RuntimeError(f"Google Calendar rejected the delete: {msg}")
    raise RuntimeError("unreachable")  # loop always returns or raises


def _format_api_event(item: dict) -> dict:
    """Map a Calendar API event resource to EVE's spoken-calendar event shape
    (the same dict parse_ics_events produces, so get_calendar consumers and
    calendar_watch.py work unchanged)."""
    start = item.get("start", {})
    all_day = "date" in start and "dateTime" not in start
    raw = start.get("dateTime") or start.get("date") or ""
    try:
        when = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        if when.tzinfo is not None:
            when = when.astimezone().replace(tzinfo=None)  # local naive, like ICS path
    except ValueError:
        return {}
    return {
        "what": item.get("summary", "(no title)"),
        "when": f"{when:%a %b %d}" + ("" if all_day else f" {when:%I:%M %p}"),
        "starts_at": when.isoformat(),
        "all_day": all_day,
        "_sort": when,
    }


async def list_events(days: int = 2, calendar_id: str = "primary") -> list:
    """Events from now-start-of-day through +days, in EVE's event shape.

    The same consent that enables writes covers this read — it replaces the
    secret-ICS-URL hack when the native connection is active.
    """
    window_start = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    window_end = window_start + timedelta(days=max(1, min(14, days)))
    url = f"{_API_BASE}/calendars/{calendar_id}/events"
    params = {
        "singleEvents": "true",
        "orderBy": "startTime",
        "maxResults": 50,
        "timeMin": window_start.astimezone().isoformat(),
        "timeMax": window_end.astimezone().isoformat(),
    }
    async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
        data = await _authed_json(session, "GET", url, params=params)
    events = [e for e in (_format_api_event(i) for i in data.get("items", [])) if e]
    events.sort(key=lambda e: e["_sort"])
    for e in events:
        del e["_sort"]
    return events[:20]
