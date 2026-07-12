import sys
import types
from unittest.mock import MagicMock

from pipecat.frames.frames import TranscriptionFrame, ErrorFrame


def _install_fake_riva(transcript="turn on the lights", raise_exc=None):
    fake = types.ModuleType("riva")
    fake.client = types.ModuleType("riva.client")
    auth = MagicMock()
    asr = MagicMock()
    if raise_exc:
        asr.offline_recognize.side_effect = raise_exc
    else:
        resp = MagicMock()
        resp.results[0].alternatives[0].transcript = transcript
        asr.offline_recognize.return_value = resp
    fake.client.Auth = MagicMock(return_value=auth)
    fake.client.ASRService = MagicMock(return_value=asr)
    fake.client.RecognitionConfig = MagicMock()
    fake.client.AudioEncoding = MagicMock(LINEAR_PCM=1)
    sys.modules["riva"] = fake
    sys.modules["riva.client"] = fake.client
    return fake


async def _collect(agen):
    return [f async for f in agen]


# Repo runs pytest-asyncio in asyncio_mode=auto — async def + await.
async def test_riva_stt_emits_final_transcription(monkeypatch):
    _install_fake_riva("turn on the lights")
    from riva_stt import RivaSTTService
    svc = RivaSTTService()
    svc._user_id = "owner"
    frames = await _collect(svc.run_stt(b"\x00\x00" * 16000))
    texts = [f.text for f in frames if isinstance(f, TranscriptionFrame)]
    assert texts == ["turn on the lights"]


async def test_riva_stt_error_yields_errorframe_not_crash(monkeypatch):
    _install_fake_riva(raise_exc=RuntimeError("riva down"))
    from riva_stt import RivaSTTService
    svc = RivaSTTService()
    svc._user_id = "owner"
    frames = await _collect(svc.run_stt(b"\x00" * 100))
    assert any(isinstance(f, ErrorFrame) for f in frames)
