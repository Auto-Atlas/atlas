# Suite-wide hermeticity guards.
import pytest


@pytest.fixture(autouse=True)
def _no_calendar_connector_probe(monkeypatch):
    """Keep unit tests offline: add_calendar_event probes the local OpenJarvis
    daemon (:8000) for the gcalendar connector by default, which would make
    webhook-path tests depend on whatever daemon happens to be running on the
    dev box. Tests that exercise the connector path re-enable it explicitly
    and patch the client."""
    monkeypatch.setenv("EVE_CAL_CONNECTOR", "0")


@pytest.fixture(autouse=True)
def _isolate_nag_store(monkeypatch, tmp_path):
    """The ack loop (nag_store) is written from calendar_source and reminder fires —
    without this every calendar/reminder test would grow the repo's real nags.json."""
    monkeypatch.setenv("EVE_NAG_FILE", str(tmp_path / "nags.json"))


@pytest.fixture(autouse=True)
def _isolate_native_google(monkeypatch, tmp_path):
    """Point the native Google Calendar connection at pristine per-test state so
    the developer's real ~/.eve token file / .env client never leaks into tests."""
    monkeypatch.delenv("EVE_GOOGLE_CLIENT_ID", raising=False)
    monkeypatch.delenv("EVE_GOOGLE_CLIENT_SECRET", raising=False)
    monkeypatch.setenv("EVE_GOOGLE_TOKEN_PATH", str(tmp_path / "gtoken.json"))
