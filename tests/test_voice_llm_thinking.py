# Tests for the per-turn thinking toggle in voice_llm (Epic T).
import importlib

import pytest


@pytest.fixture
def vl():
    import voice_llm
    importlib.reload(voice_llm)
    return voice_llm


def test_ollama_off_is_fast(vl, monkeypatch):
    monkeypatch.setattr(vl, "LLM_API", "ollama")
    out = vl._reasoning_extra({}, thinking=False)
    assert out["reasoning_effort"] == "none"


def test_ollama_on_reasons(vl, monkeypatch):
    monkeypatch.setattr(vl, "LLM_API", "ollama")
    out = vl._reasoning_extra({}, thinking=True)
    assert out["reasoning_effort"] == "medium"


def test_ollama_on_honors_effort_env(vl, monkeypatch):
    monkeypatch.setattr(vl, "LLM_API", "ollama")
    monkeypatch.setenv("EVE_THINKING_EFFORT", "high")
    out = vl._reasoning_extra({}, thinking=True)
    assert out["reasoning_effort"] == "high"


def test_vllm_dialect_uses_enable_thinking(vl, monkeypatch):
    monkeypatch.setattr(vl, "LLM_API", "vllm")
    assert vl._reasoning_extra({}, thinking=True)["chat_template_kwargs"]["enable_thinking"] is True
    assert vl._reasoning_extra({}, thinking=False)["chat_template_kwargs"]["enable_thinking"] is False


def test_thinking_enabled_degrades_to_false(vl, monkeypatch):
    # A broken thinking_state must never break the voice request build.
    import thinking_state
    monkeypatch.setattr(thinking_state, "enabled", lambda: (_ for _ in ()).throw(RuntimeError()))
    assert vl._thinking_enabled() is False


def test_unknown_provider_falls_back_to_ollama(vl, monkeypatch):
    monkeypatch.setattr(vl, "LLM_API", "some-future-provider")
    out = vl._reasoning_extra({}, thinking=True)
    assert out["reasoning_effort"] == "medium"   # safe default dialect, never crashes


def test_new_provider_plugs_in_with_one_registration(vl, monkeypatch):
    # The extensibility contract: adding a new LLM service's thinking control is a single call.
    def apply_openai(extra, thinking):
        extra["reasoning_effort"] = "high" if thinking else "minimal"
        return extra

    vl.register_thinking_applier("openai", apply_openai)
    monkeypatch.setattr(vl, "LLM_API", "openai")
    assert vl._reasoning_extra({}, thinking=True)["reasoning_effort"] == "high"
    assert vl._reasoning_extra({}, thinking=False)["reasoning_effort"] == "minimal"
