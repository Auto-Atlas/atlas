# Tests for the dynamic multi-brain voice_llm factory (both OpenAI + Anthropic styles).
import importlib
import json

import pytest

# A vLLM profile injected the SAME way a real box adds a private endpoint — via
# EVE_BRAIN_PROFILES — so these tests exercise the vllm dialect WITHOUT depending on
# any hardcoded personal tailnet IP in shared source (which would violate the
# multi-tenant rule). all_profiles() re-reads this env on every call, so no reload.
_VLLM_PROFILES = json.dumps(
    {"gpu-box": {"api": "vllm", "base_url": "http://test-rig:8080/v1", "model": "test-122b"}}
)


@pytest.fixture
def vl():
    import voice_llm
    importlib.reload(voice_llm)
    return voice_llm


def test_active_profile_switches_by_env(vl, monkeypatch):
    monkeypatch.setenv("EVE_BRAIN_PROFILES", _VLLM_PROFILES)
    monkeypatch.setenv("JARVIS_VOICE_BRAIN", "gpu-box")
    p = vl.active_profile()
    assert p["_name"] == "gpu-box" and p["api"] == "vllm"
    monkeypatch.setenv("JARVIS_VOICE_BRAIN", "zai")
    p = vl.active_profile()
    assert p["_name"] == "zai" and p["api"] == "anthropic" and p["model"] == "glm-5.2"


def test_extra_profiles_override_and_no_hardcoded_ips(vl, monkeypatch):
    # Shared source must NOT ship personal endpoints; private brains come from config.
    for prof in vl.BUILTIN_PROFILES.values():
        assert "100." not in prof["base_url"], "no hardcoded tailnet IP in BUILTIN_PROFILES"
    monkeypatch.setenv("EVE_BRAIN_PROFILES", _VLLM_PROFILES)
    assert "gpu-box" in vl.all_profiles()          # merged over builtins
    assert "ollama" in vl.all_profiles()            # builtins still present


def test_unknown_brain_falls_back_loudly(vl, monkeypatch, capsys):
    monkeypatch.delenv("EVE_BRAIN_PROFILES", raising=False)
    monkeypatch.setenv("JARVIS_VOICE_BRAIN", "no-such-rig")
    monkeypatch.setenv("JARVIS_LLM_API", "ollama")
    p = vl.active_profile()
    assert p["api"] == "ollama"
    assert "no-such-rig" in capsys.readouterr().out  # loud, not silent


def test_legacy_env_profile_still_works(vl, monkeypatch):
    monkeypatch.delenv("JARVIS_VOICE_BRAIN", raising=False)
    monkeypatch.delenv("EVE_BRAIN_PROFILES", raising=False)
    monkeypatch.setenv("JARVIS_LLM_API", "vllm")
    monkeypatch.setenv("JARVIS_LLM_BASE_URL", "http://x:8080/v1")
    monkeypatch.setenv("OLLAMA_MODEL", "some-model")
    p = vl.active_profile()
    assert p["api"] == "vllm" and p["base_url"] == "http://x:8080/v1" and p["model"] == "some-model"


def test_instr_role_per_dialect(vl, monkeypatch):
    monkeypatch.setenv("EVE_BRAIN_PROFILES", _VLLM_PROFILES)
    monkeypatch.setenv("JARVIS_VOICE_BRAIN", "zai")       # anthropic
    assert vl.instr_role() == "user"
    monkeypatch.setenv("JARVIS_VOICE_BRAIN", "gpu-box")  # vllm
    assert vl.instr_role() == "user"
    monkeypatch.setenv("JARVIS_VOICE_BRAIN", "ollama")
    assert vl.instr_role() == "system"


def test_instr_role_for_pins_to_profile(vl):
    # The session-pinned role tracks the GIVEN profile, not the live setting — this is
    # what stops a mid-session brain switch from drifting the injected-message role.
    assert vl.instr_role_for({"api": "ollama"}) == "system"
    assert vl.instr_role_for({"api": "vllm"}) == "user"
    assert vl.instr_role_for({"api": "anthropic"}) == "user"
    assert vl.instr_role_for({"api": "zai"}) == "user"


def test_resolve_key_profile_source_wins_over_global_env(vl, monkeypatch):
    # A box-wide JARVIS_LLM_API_KEY (set for a legacy endpoint) must NOT leak onto a
    # zai/anthropic brain and 401 it — the profile's own key source wins.
    monkeypatch.setenv("JARVIS_LLM_API_KEY", "legacy-endpoint-key")
    monkeypatch.setenv("EVE_ZAI_API_KEY", "zai-real-key")
    assert vl._resolve_key({"key": "zai"}) == "zai-real-key"
    # explicit per-profile api_key also wins over the global
    assert vl._resolve_key({"api_key": "prof-key"}) == "prof-key"
    # legacy 'env' profile (no key source) still falls back to the global
    assert vl._resolve_key({}) == "legacy-endpoint-key"


def test_zai_thinking_applier(vl):
    assert vl._apply_zai({}, True)["thinking"]["type"] == "enabled"
    assert vl._apply_zai({}, False)["thinking"]["type"] == "disabled"


def test_factory_builds_openai_style(vl, monkeypatch):
    monkeypatch.setenv("JARVIS_VOICE_BRAIN", "ollama")
    svc = vl.make_voice_llm()
    assert type(svc).__name__ == "VoiceOllama"
    assert svc._voice_api == "ollama"


def test_factory_accepts_pinned_profile(vl):
    # make_voice_llm(profile) builds from the PASSED profile, so the session's LLM and
    # its pinned instr_role come from one resolution (no re-resolve race).
    svc = vl.make_voice_llm({"api": "ollama", "base_url": "http://localhost:11434/v1", "model": "qwen3:8b"})
    assert type(svc).__name__ == "VoiceOllama" and svc._voice_api == "ollama"


def test_factory_builds_anthropic_style(vl, monkeypatch):
    monkeypatch.setenv("JARVIS_VOICE_BRAIN", "zai")
    svc = vl.make_voice_llm()
    assert "Anthropic" in type(svc).__name__  # AnthropicLLMService pointed at Z.AI
