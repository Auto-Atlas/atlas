"""Per-device credential registry — Phase 2A (frozen Spec 6).

Replaces the single shared, long-lived, plaintext bearer token (`pairing.py` /
`approval_token.txt`) with **per-device credentials**, server-stored **hashed**
(argon2id, never plaintext), each revocable and rotatable. Pairing happens via a
one-time **bootstrap code** (short-lived, single-use, rate-limited) — the QR /
entry carries the code, not a durable token.

PURE storage/crypto — no FastAPI, no pipecat. SQLite-backed (mirrors
`approval_store`'s one-connection-per-op style). Path: ``EVE_DEVICE_DB`` (default
``~/jarvis-sidecar/devices.db``).

Design choices (Spec 6 + REVIEW forks):
- **Device secret** → argon2id (resist offline brute force if the DB leaks).
- **Bootstrap code** → high-entropy (~64 bits) + SHA-256 for O(1) lookup; safe
  because it is ephemeral (10 min), single-use, and rate-limited (≤5 attempts).
- **Dual-accept migration:** ``verify_or_legacy`` also accepts the old shared
  token while ``EVE_ALLOW_LEGACY_SHARED_TOKEN`` is set, so a paired phone can
  upgrade; close the window (unset the env) once migrated, then the shared token
  is dead.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import sqlite3
import time
from pathlib import Path

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError

_PH = PasswordHasher()  # argon2id defaults

BOOTSTRAP_TTL_S = 600          # 10 min (Spec 6)
BOOTSTRAP_MAX_ATTEMPTS = 5     # per-code brute-force ceiling -> then dead
_MINT_WINDOW_S = 3600
_MINT_MAX_PER_WINDOW = 20      # anti-abuse on code minting


def _db_path() -> Path:
    return Path(os.getenv("EVE_DEVICE_DB", str(Path.home() / "jarvis-sidecar" / "devices.db")))


def _conn() -> sqlite3.Connection:
    p = _db_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(p))
    conn.execute("PRAGMA journal_mode=WAL")
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS bootstrap_codes (
            code_sha   TEXT PRIMARY KEY,
            created_at REAL NOT NULL,
            expires_at REAL NOT NULL,
            used       INTEGER NOT NULL DEFAULT 0,
            attempts   INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE IF NOT EXISTS devices (
            device_id   TEXT PRIMARY KEY,
            secret_hash TEXT NOT NULL,
            label       TEXT NOT NULL DEFAULT '',
            created_at  REAL NOT NULL,
            revoked     INTEGER NOT NULL DEFAULT 0,
            revoked_at  REAL
        );
        """
    )
    return conn


def _sha(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()


def db_exists() -> bool:
    return _db_path().is_file()


# --- bootstrap codes -------------------------------------------------------

def mint_bootstrap_code(*, ttl_s: int = BOOTSTRAP_TTL_S, now: float | None = None) -> str:
    """Mint a one-time pairing code. Returns the PLAINTEXT code (shown once / in the QR).

    Rate-limited: refuses if more than _MINT_MAX_PER_WINDOW codes were minted in
    the last hour (anti-abuse). Only the SHA-256 of the code is stored.
    """
    now = time.time() if now is None else now
    with _conn() as c:
        recent = c.execute(
            "SELECT COUNT(*) FROM bootstrap_codes WHERE created_at > ?",
            (now - _MINT_WINDOW_S,),
        ).fetchone()[0]
        if recent >= _MINT_MAX_PER_WINDOW:
            raise RuntimeError("bootstrap code minting rate exceeded — try again later")
        code = secrets.token_urlsafe(8)  # ~64 bits
        c.execute(
            "INSERT INTO bootstrap_codes (code_sha, created_at, expires_at) VALUES (?,?,?)",
            (_sha(code), now, now + ttl_s),
        )
    return code


def redeem_bootstrap_code(
    code: str, *, label: str = "", now: float | None = None
) -> tuple[str, str] | None:
    """Redeem a code → mint a per-device credential. Returns (device_id, secret)
    once, or None if the code is invalid/expired/used/exhausted.

    Single-use + per-code attempt ceiling. The returned secret is the only time
    it exists in plaintext; only its argon2id hash is stored.
    """
    now = time.time() if now is None else now
    code_sha = _sha(code)
    with _conn() as c:
        row = c.execute(
            "SELECT expires_at, used, attempts FROM bootstrap_codes WHERE code_sha=?",
            (code_sha,),
        ).fetchone()
        if row is None:
            return None  # unknown code — nothing to rate-limit against (no row)
        expires_at, used, attempts = row
        if used or attempts >= BOOTSTRAP_MAX_ATTEMPTS or now > expires_at:
            return None
        # mint the device credential
        device_id = secrets.token_hex(8)
        secret = secrets.token_urlsafe(32)
        c.execute(
            "INSERT INTO devices (device_id, secret_hash, label, created_at) VALUES (?,?,?,?)",
            (device_id, _PH.hash(secret), label, now),
        )
        c.execute("UPDATE bootstrap_codes SET used=1 WHERE code_sha=?", (code_sha,))
    return device_id, secret


def _note_failed_attempt(code: str) -> None:
    """Increment the attempt counter for a (possibly wrong) code, if it exists."""
    with _conn() as c:
        c.execute(
            "UPDATE bootstrap_codes SET attempts = attempts + 1 WHERE code_sha=?",
            (_sha(code),),
        )


# --- device credentials ----------------------------------------------------

def verify_credential(device_id: str, secret: str) -> bool:
    """True iff device_id exists, is not revoked, and secret matches (argon2id)."""
    with _conn() as c:
        row = c.execute(
            "SELECT secret_hash, revoked FROM devices WHERE device_id=?", (device_id,)
        ).fetchone()
    if row is None:
        return False
    secret_hash, revoked = row
    if revoked:
        return False
    try:
        return _PH.verify(secret_hash, secret)
    except VerifyMismatchError:
        return False
    except Exception:
        return False


def revoke_device(device_id: str, *, now: float | None = None) -> bool:
    """Revoke a device. Subsequent verify_credential fails immediately. Idempotent."""
    now = time.time() if now is None else now
    with _conn() as c:
        cur = c.execute(
            "UPDATE devices SET revoked=1, revoked_at=? WHERE device_id=? AND revoked=0",
            (now, device_id),
        )
        return cur.rowcount > 0


def rotate_device(device_id: str) -> str | None:
    """Issue a NEW secret for an existing (non-revoked) device; the old secret
    stops working immediately. Returns the new plaintext secret, or None if the
    device is unknown/revoked. (Grace-overlap rotation is a later refinement.)"""
    new_secret = secrets.token_urlsafe(32)
    with _conn() as c:
        cur = c.execute(
            "UPDATE devices SET secret_hash=? WHERE device_id=? AND revoked=0",
            (_PH.hash(new_secret), device_id),
        )
        if cur.rowcount == 0:
            return None
    return new_secret


def list_devices() -> list[dict]:
    """Audit view: every device with its revoked state (never exposes secrets)."""
    with _conn() as c:
        rows = c.execute(
            "SELECT device_id, label, created_at, revoked, revoked_at FROM devices "
            "ORDER BY created_at"
        ).fetchall()
    return [
        {"device_id": r[0], "label": r[1], "created_at": r[2],
         "revoked": bool(r[3]), "revoked_at": r[4]}
        for r in rows
    ]


# --- dual-accept migration (Spec 6 fork) -----------------------------------

def _legacy_window_open() -> bool:
    return os.getenv("EVE_ALLOW_LEGACY_SHARED_TOKEN", "") == "1"


def verify_or_legacy(device_id: str | None, secret: str | None, *, legacy_shared_token: str) -> bool:
    """Accept EITHER a valid per-device credential OR (while the migration window
    is open) the old shared token presented as ``secret``. Constant-time on the
    legacy compare. Close the window (unset EVE_ALLOW_LEGACY_SHARED_TOKEN) once
    every device has upgraded; then the shared token is dead.
    """
    if device_id and secret and verify_credential(device_id, secret):
        return True
    if _legacy_window_open() and legacy_shared_token and secret:
        return hmac.compare_digest(secret, legacy_shared_token)
    return False
