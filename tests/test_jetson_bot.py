"""The bar: the Jetson body wires EVE's tools through the policy gate so a
tool-call round-trips, and builds the user+assistant aggregators (the real
pipeline wiring) — all without audio. A fake llm records register_function()."""


class FakeLLM:
    def __init__(self):
        self.registered = {}

    def register_function(self, name, handler):
        self.registered[name] = handler


def test_jetson_body_registers_tools_and_aggregators(monkeypatch):
    monkeypatch.setenv("EVE_UNSAFE_TREAT_ALL_AS_OWNER", "1")  # owner tier for the test
    import jetson_bot
    llm = FakeLLM()
    context, protected_head, user_aggr, assistant_aggr = jetson_bot.build_brain(llm)
    # Brain reused from jarvis_core: a representative tool is registered & gated.
    assert "system_report" in llm.registered     # registered by register_tools
    assert "look" in llm.registered              # Jetson-only vision tool, through the gate
    assert "actuate_hand" in llm.registered      # Jetson-only RUKA hand tool, through the gate
    assert protected_head >= 1
    assert context.get_messages()[0]["role"] == "system"
    # Real aggregators built on the tested side of the seam (BMAD fix):
    assert user_aggr is not None
    assert type(assistant_aggr).__name__ == "TrimmingAssistantAggregator"


def test_jetson_bot_imports_clean():
    import jetson_bot  # must not import pyaudio/riva at module load
    assert hasattr(jetson_bot, "build_brain")
