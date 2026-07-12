#
# The voice LLM service, shared by every Jarvis body (bot.py desktop,
# phone_bot.py WebRTC). Supports BOTH endpoint styles, selected at runtime:
#
#   * OpenAI-compatible  (ollama / vllm / zai-paas) via pipecat OLLamaLLMService
#   * Anthropic-compatible (real Anthropic, or Z.AI's /api/anthropic) via
#     pipecat AnthropicLLMService with a client pointed at the endpoint.
#
# DYNAMIC brain switching: the ACTIVE brain is a named profile resolved FRESH
# on every make_voice_llm() call (i.e. per voice session), so you can switch
# back and forth without editing code — flip the settings-store key
# "voice_brain" (app-switchable) or env JARVIS_VOICE_BRAIN, then reconnect.
# Legacy JARVIS_LLM_* env still works as an implicit profile.
#
# Qwen3/GLM hybrid reasoning is right for agents but fatal for voice: on
# user-role turns it can burn the whole token budget "thinking" and emit NO
# speakable content. Each dialect has its own off switch (below); voice
# defaults to thinking OFF (fast).
#
import json
import os
from pathlib import Path

from pipecat.services.ollama.llm import OLLamaLLMService

# ---- Named brain profiles ---------------------------------------------------
# Each: api dialect, endpoint, model, and optional key source ("zai" => Z.AI token
# from env, see _zai_key). Only NEUTRAL, non-tenant endpoints live here: localhost
# ollama and the PUBLIC api.z.ai endpoints. Box-specific brains (a LAN vLLM rig on
# a private tailnet, etc.) must NOT be hardcoded in shared source — add them per box
# via EVE_BRAIN_PROFILES (JSON) or a gitignored profiles.local.json (see
# _load_extra_profiles). Keeps this file multi-tenant-neutral.
_ZAI_ANTHROPIC = "https://api.z.ai/api/anthropic"
_ZAI_OPENAI = "https://api.z.ai/api/paas/v4"

BUILTIN_PROFILES = {
    # OpenAI-style endpoints
    "ollama":     {"api": "ollama", "base_url": "http://localhost:11434/v1",       "model": "qwen3:8b"},
    "zai-openai": {"api": "zai",    "base_url": _ZAI_OPENAI,   "model": "glm-4.6",  "key": "zai"},
    # Anthropic-style endpoints
    "zai":        {"api": "anthropic", "base_url": _ZAI_ANTHROPIC, "model": "glm-5.2", "key": "zai"},
}

# Owner-defined profiles (a home vLLM rig on the tailnet, a lab server, ...)
# live OUTSIDE the repo in a gitignored JSON file — same shape as
# BUILTIN_PROFILES, merged over it (an owner profile may shadow a builtin):
#   {"my-rig": {"api": "vllm", "base_url": "http://my-rig:8080/v1", "model": "Qwen3.5-122B-A10B"}}
_BRAINS_FILE = Path(os.getenv("EVE_BRAINS_FILE", str(Path(__file__).parent / "brains.local.json")))


def _owner_profiles() -> dict:
    """Read fresh per resolution (same policy as the settings store) so edits
    apply on the next voice session. A malformed file raises — a brain the
    owner explicitly wrote must never be silently skipped."""
    if not _BRAINS_FILE.exists():
        return {}
    data = json.loads(_BRAINS_FILE.read_text(encoding="utf-8"))
    return {str(name): dict(profile) for name, profile in data.items()}


def _load_extra_profiles() -> dict:
    """Per-box/per-tenant brain profiles, so private endpoints are never hardcoded
    in shared source. Source order: EVE_BRAIN_PROFILES (a JSON object) wins, else a
    gitignored profiles.local.json next to this module. Same shape as a
    BUILTIN_PROFILES value ({api, base_url, model, [key]}). Never raises — returns {}
    on any error so a bad config can't break a voice turn."""
    raw = os.getenv("EVE_BRAIN_PROFILES")
    if raw:
        try:
            return json.loads(raw)
        except Exception:
            return {}
    try:
        p = Path(__file__).with_name("profiles.local.json")
        if p.exists():
            return json.loads(p.read_text())
    except Exception:
        pass
    return {}


def all_profiles() -> dict:
    """Builtins overlaid with per-box extras — the full set of selectable brains.
    Used for resolution AND for validating an app-supplied voice_brain name."""
    return {**BUILTIN_PROFILES, **_load_extra_profiles()}


def _zai_key():
    """Z.AI token: env first (EVE_ZAI_API_KEY / ZAI_API_KEY, the neutral path), then
    the agent-tier settings file as a fallback for the dev box. Never raises."""
    for var in ("EVE_ZAI_API_KEY", "ZAI_API_KEY"):
        v = os.getenv(var)
        if v:
            return v
    try:
        cfg = json.loads((Path.home() / ".claude-local-glm" / "settings.json").read_text())["env"]
        return cfg.get("ANTHROPIC_AUTH_TOKEN") or None
    except Exception:
        return None


def _resolve_key(profile: dict):
    # The profile's OWN key source wins, so a box-wide JARVIS_LLM_API_KEY set for a
    # legacy endpoint can't leak onto a zai/anthropic brain and 401 every turn. The
    # global env is only the fallback for the legacy 'env' profile (no key source).
    if profile.get("key") == "zai":
        return _zai_key()
    if profile.get("api_key"):
        return profile["api_key"]
    return os.getenv("JARVIS_LLM_API_KEY")


def _settings_brain():
    """The app-flippable active-brain selector (settings table), read fresh.
    Degrades to None on any error so a store hiccup never breaks a voice turn."""
    try:
        import approval_store
        v = approval_store.get_setting("voice_brain")
        return v.strip() if v else None
    except Exception:
        return None


def active_profile() -> dict:
    """Resolve the ACTIVE brain FRESH: settings store -> JARVIS_VOICE_BRAIN env
    -> legacy JARVIS_LLM_* env -> ollama default. Returned dict always carries
    api/base_url/model plus a _name for logging."""
    name = _settings_brain() or os.getenv("JARVIS_VOICE_BRAIN")
    profiles = all_profiles()
    if name and name in profiles:
        return dict(profiles[name], _name=name)
    if name:
        # A named brain that resolves nowhere is a config error — say so loudly
        # instead of quietly answering with a different model.
        print(f"voice_llm: brain profile '{name}' not found in builtins, "
              "EVE_BRAIN_PROFILES or profiles.local.json — falling back to "
              "the JARVIS_LLM_* env profile.")
    # Legacy implicit profile from the existing JARVIS_LLM_* env vars.
    base = os.getenv("JARVIS_LLM_BASE_URL", "http://localhost:11434/v1")
    return {
        "_name": name or "env",
        "api": os.getenv("JARVIS_LLM_API", "ollama").lower(),
        "base_url": base,
        "model": os.getenv("OLLAMA_MODEL", "qwen3:8b"),
        "key": "zai" if "z.ai" in base else None,
    }


def instr_role_for(profile: dict) -> str:
    """Injected-message role for a SPECIFIC profile. OpenAI-compat non-ollama
    templates (vLLM/GLM/Z.AI) 400 on system-only turns, so those use 'user'; plain
    Ollama uses 'system'. A session PINS this to the profile its LLM was built from
    (instr_role_for(session_profile)) so a mid-session voice_brain switch can't drift
    the role away from the live model — the new brain applies on the next reconnect,
    same as the LLM itself."""
    return "user" if profile.get("api") in ("vllm", "zai", "anthropic") else "system"


def instr_role() -> str:
    """Role for the CURRENT active dialect, resolved fresh. Correct for one-shot/boot
    use (bot.py banner); live sessions must pin via instr_role_for(session_profile)."""
    return instr_role_for(active_profile())


# Back-compat module values (import-time snapshot of the default active brain).
# The bodies call instr_role()/active_profile() for the DYNAMIC value.
_ACTIVE = active_profile()
LLM_API = _ACTIVE["api"]
LLM_BASE_URL = _ACTIVE["base_url"]
OLLAMA_MODEL = _ACTIVE["model"]
INSTR_ROLE = instr_role()


def _thinking_enabled() -> bool:
    """The manual thinking toggle (Epic T), read fresh per request build. Degrades to False
    (fast) on any error so a store hiccup can never break a voice turn."""
    try:
        import thinking_state
        return thinking_state.enabled()
    except Exception:
        return False


# ---- Per-provider thinking translation --------------------------------------
# thinking_state.enabled() is the provider-AGNOSTIC switch. Each applier translates that ONE
# boolean into that dialect's reasoning knob. Register a new one to add a provider.
def _apply_ollama(extra: dict, thinking: bool) -> dict:
    extra["reasoning_effort"] = (
        os.getenv("EVE_THINKING_EFFORT", "medium") if thinking
        else os.getenv("OLLAMA_REASONING_EFFORT", "none"))
    return extra


def _apply_vllm(extra: dict, thinking: bool) -> dict:
    extra["chat_template_kwargs"] = {"enable_thinking": thinking}
    return extra


def _apply_zai(extra: dict, thinking: bool) -> dict:
    # Z.AI OpenAI-compat (GLM): a top-level "thinking" object.
    extra["thinking"] = {"type": "enabled" if thinking else "disabled"}
    return extra


_THINKING_APPLIERS = {"ollama": _apply_ollama, "vllm": _apply_vllm, "zai": _apply_zai}


def register_thinking_applier(api: str, applier) -> None:
    """Register how a new LLM service expresses thinking on/off. `applier(extra, thinking)->extra`."""
    _THINKING_APPLIERS[api] = applier


def _reasoning_extra(extra: dict, thinking: bool, api: str = None) -> dict:
    """Translate the thinking switch to the given dialect's knob (defaults to the
    module LLM_API for back-compat). Unknown dialect -> Ollama dialect, never crashes."""
    return _THINKING_APPLIERS.get(api or LLM_API, _apply_ollama)(extra, thinking)


class VoiceOllama(OLLamaLLMService):
    """OpenAI-compatible voice brain (Ollama / vLLM / Z.AI paas)."""

    def __init__(self, *args, voice_api: str = "ollama", voice_model: str = "", **kwargs):
        super().__init__(*args, **kwargs)
        self._voice_api = voice_api
        self._voice_model = voice_model

    def build_chat_completion_params(self, params_from_context):
        params = super().build_chat_completion_params(params_from_context)
        api = self._voice_api
        reasoning = api in ("vllm", "zai") or (
            api == "ollama" and self._voice_model.lower().startswith("qwen3"))
        if reasoning:
            extra = dict(params.get("extra_body") or {})
            params["extra_body"] = _reasoning_extra(extra, _thinking_enabled(), api)
        return params


def _make_anthropic(profile: dict, temperature: float, max_tokens: int):
    """Anthropic-compatible voice brain (real Anthropic, or Z.AI /api/anthropic).
    A pre-built AsyncAnthropic client carries the endpoint + key. Thinking stays
    OFF for voice (fast); the OpenAI/vLLM brains carry the full thinking toggle."""
    from anthropic import AsyncAnthropic
    from pipecat.services.anthropic.llm import AnthropicLLMService

    key = _resolve_key(profile) or ""
    client = AsyncAnthropic(base_url=profile["base_url"], api_key=key)
    return AnthropicLLMService(
        client=client,
        api_key=key,  # required kwarg even when a client is supplied
        settings=AnthropicLLMService.Settings(
            model=profile["model"], temperature=temperature, max_tokens=max_tokens),
    )


def make_voice_llm(profile: dict | None = None):
    """Build the voice brain. Pass the profile resolved once at session start (so the
    LLM and the pinned instr_role agree for the whole session); omit it to resolve
    fresh (a reconnect swaps brains). Explicit sampling controls — voice replies never
    need more than a few sentences; the token cap leaves room for tool-call JSON."""
    p = profile or active_profile()
    temperature = float(os.getenv("OLLAMA_TEMPERATURE", "0.3"))
    max_tokens = int(os.getenv("OLLAMA_MAX_TOKENS", "300"))

    if p["api"] == "anthropic":
        return _make_anthropic(p, temperature, max_tokens)

    svc = VoiceOllama(
        base_url=p["base_url"],
        voice_api=p["api"],
        voice_model=p["model"],
        settings=OLLamaLLMService.Settings(
            model=p["model"], temperature=temperature, max_tokens=max_tokens,
        ),
    )
    key = _resolve_key(p)
    if key:
        try:
            svc._client.api_key = key
        except Exception:
            pass
    # Resilience: bump SDK retries so a brief model reload (503) is a pause EVE
    # rides out, not a torn-down session. Guarded against SDK-internals changes.
    try:
        svc._client.max_retries = int(os.getenv("JARVIS_LLM_MAX_RETRIES", "5"))
    except Exception:
        pass
    return svc
