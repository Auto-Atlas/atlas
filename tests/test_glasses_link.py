# Tests for glasses_link — the one place every glasses knob is read from env. Pins the
# product-safe defaults (nothing owner-specific baked in) and the env overrides, so the
# endpoint contract's documented names can never drift from the code that reads them.
import importlib


def _fresh(monkeypatch, **env):
    for k in ("EVE_GLASSES_ENABLED", "EVE_GLASSES_RTMP_PORT", "EVE_GLASSES_RTMP_APP",
              "EVE_GLASSES_SAMPLE_FPS", "EVE_GLASSES_NARRATE_MIN_S", "EVE_GLASSES_NARRATE_URL",
              "EVE_VLM_URL", "EVE_VLM_MODEL"):
        monkeypatch.delenv(k, raising=False)
    for k, v in env.items():
        monkeypatch.setenv(k, v)
    import glasses_link
    importlib.reload(glasses_link)
    return glasses_link


def test_defaults_are_product_safe(monkeypatch):
    gl = _fresh(monkeypatch)
    cfg = gl.load()
    assert cfg.enabled is False              # master gate off by default
    assert cfg.rtmp_port == 1935
    assert cfg.rtmp_app == "eve"
    assert cfg.sample_fps == 1.0
    assert cfg.narrate_min_s == 8.0
    assert cfg.narrate_url == ""             # empty => log-only
    assert cfg.vlm_url == "http://127.0.0.1:8093"
    assert cfg.vlm_model == "qwen3-vl"
    # Derived push target.
    assert cfg.rtmp_url == "rtmp://0.0.0.0:1935/eve"


def test_env_overrides_every_knob(monkeypatch):
    gl = _fresh(
        monkeypatch,
        EVE_GLASSES_ENABLED="1",
        EVE_GLASSES_RTMP_PORT="1940",
        EVE_GLASSES_RTMP_APP="pov",
        EVE_GLASSES_SAMPLE_FPS="2.5",
        EVE_GLASSES_NARRATE_MIN_S="4",
        EVE_GLASSES_NARRATE_URL="http://127.0.0.1:8790/narrate",
        EVE_VLM_URL="http://gpu:9000/",
        EVE_VLM_MODEL="qwen3-vl-32b",
    )
    cfg = gl.load()
    assert cfg.enabled is True
    assert cfg.rtmp_port == 1940
    assert cfg.rtmp_app == "pov"
    assert cfg.sample_fps == 2.5
    assert cfg.narrate_min_s == 4.0
    assert cfg.narrate_url == "http://127.0.0.1:8790/narrate"
    assert cfg.vlm_url == "http://gpu:9000"      # trailing slash stripped
    assert cfg.vlm_model == "qwen3-vl-32b"
    assert cfg.rtmp_url == "rtmp://0.0.0.0:1940/pov"


def test_garbage_numeric_falls_back_not_crashes(monkeypatch):
    gl = _fresh(monkeypatch, EVE_GLASSES_SAMPLE_FPS="not-a-number",
                EVE_GLASSES_RTMP_PORT="", EVE_GLASSES_NARRATE_MIN_S=" ")
    cfg = gl.load()
    assert cfg.sample_fps == 1.0
    assert cfg.rtmp_port == 1935
    assert cfg.narrate_min_s == 8.0


def test_enabled_accepts_common_truthy_spellings(monkeypatch):
    for val in ("1", "true", "TRUE", "yes", "on"):
        assert _fresh(monkeypatch, EVE_GLASSES_ENABLED=val).load().enabled is True
    for val in ("0", "false", "no", "off", ""):
        assert _fresh(monkeypatch, EVE_GLASSES_ENABLED=val).load().enabled is False
