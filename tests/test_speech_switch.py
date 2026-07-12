import sys
import types
from unittest.mock import MagicMock


def _fake_riva():
    fake = types.ModuleType("riva")
    fake.client = types.ModuleType("riva.client")
    for n in ("Auth", "ASRService", "SpeechSynthesisService", "RecognitionConfig"):
        setattr(fake.client, n, MagicMock())
    fake.client.AudioEncoding = MagicMock(LINEAR_PCM=1)
    sys.modules["riva"] = fake
    sys.modules["riva.client"] = fake.client


# _build_stt/_build_tts read os.getenv at CALL time, so no reload needed —
# just set the env and call (avoids re-running the kokoro monkeypatch).
def test_stt_switch_riva(monkeypatch):
    _fake_riva()
    monkeypatch.setenv("JARVIS_STT", "riva")
    import speech_factory
    assert type(speech_factory._build_stt()).__name__ == "RivaSTTService"


def test_tts_switch_riva(monkeypatch):
    _fake_riva()
    monkeypatch.setenv("JARVIS_TTS", "riva")
    import speech_factory
    assert type(speech_factory._build_tts()).__name__ == "RivaTTSService"


def test_stt_default_still_whisper(monkeypatch):
    monkeypatch.delenv("JARVIS_STT", raising=False)
    monkeypatch.setenv("EVE_SPEAKER_ID", "disabled")  # avoid resemblyzer dep in CI
    # The assertion is about WHICH service is default, not which device — pin
    # cpu so the test passes on GPU-less machines (CI, contributor laptops).
    monkeypatch.setenv("WHISPER_DEVICE", "cpu")
    monkeypatch.setenv("WHISPER_COMPUTE", "int8")
    import speech_factory
    assert "Whisper" in type(speech_factory._build_stt()).__name__
