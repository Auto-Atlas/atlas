import importlib


def test_speech_factory_imports_without_side_effects():
    # Importing the factory must NOT bind a socket or exit (the phone_bot sins).
    mod = importlib.import_module("speech_factory")
    assert hasattr(mod, "_build_stt")
    assert hasattr(mod, "_build_tts")
    assert hasattr(mod, "SharedWhisperSTT")
    assert hasattr(mod, "MicGate")
    assert hasattr(mod, "TrimmingAssistantAggregator")  # window protection, shared by both bodies


def test_build_tts_default_is_kokoro(monkeypatch):
    monkeypatch.delenv("JARVIS_TTS", raising=False)
    import speech_factory
    tts = speech_factory._build_tts()
    assert type(tts).__name__ == "KokoroTTSService"
