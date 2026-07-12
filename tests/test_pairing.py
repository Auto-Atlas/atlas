# Tests for pairing — the phone-pairing QR payload + rendering.
from urllib.parse import parse_qs, urlsplit

import pairing


def test_build_pairing_uri_roundtrips_base_and_token():
    uri = pairing.build_pairing_uri("https://host.ts.net:8443", "tok-123")
    split = urlsplit(uri)
    assert split.scheme == "eve"
    assert split.netloc == "connect"
    q = parse_qs(split.query)
    assert q["base"] == ["https://host.ts.net:8443"]
    assert q["token"] == ["tok-123"]


def test_build_pairing_uri_url_encodes_special_chars():
    uri = pairing.build_pairing_uri("https://h:8443", "a/b+c=d")
    # The raw '://' and '+' must be encoded so the deep link survives intact.
    assert "https%3A%2F%2Fh%3A8443" in uri
    q = parse_qs(urlsplit(uri).query)
    assert q["token"] == ["a/b+c=d"]  # decodes back to the exact token


def test_render_qr_png_writes_a_real_png(tmp_path):
    path = pairing.render_qr_png("eve://connect?base=x&token=y", str(tmp_path / "qr.png"))
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"  # PNG magic — a real image was written
    assert len(data) > 100


def test_show_pairing_qr_reports_missing_config(monkeypatch, tmp_path):
    monkeypatch.setenv("EVE_APP_BASE_URL", "")
    monkeypatch.setenv("EVE_APP_TOKEN", "")
    monkeypatch.setenv("EVE_APP_TOKEN_FILE", str(tmp_path / "nope.txt"))
    r = pairing.show_pairing_qr()
    assert r["ok"] is False and "EVE_APP_BASE_URL" in r["error"]


def test_app_token_reads_file_when_env_absent(monkeypatch, tmp_path):
    monkeypatch.delenv("EVE_APP_TOKEN", raising=False)
    f = tmp_path / "tok.txt"
    f.write_text("file-token-xyz\n")
    monkeypatch.setenv("EVE_APP_TOKEN_FILE", str(f))
    assert pairing.app_token() == "file-token-xyz"
