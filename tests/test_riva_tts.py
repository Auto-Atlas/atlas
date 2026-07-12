import sys
import types
from unittest.mock import MagicMock

from pipecat.frames.frames import TTSAudioRawFrame, ErrorFrame


def _install_fake_riva(audio=b"\x01\x02" * 100, raise_exc=None):
    fake = types.ModuleType("riva")
    fake.client = types.ModuleType("riva.client")
    tts = MagicMock()
    if raise_exc:
        tts.synthesize.side_effect = raise_exc
    else:
        resp = MagicMock()
        resp.audio = audio
        tts.synthesize.return_value = resp
    fake.client.Auth = MagicMock()
    fake.client.SpeechSynthesisService = MagicMock(return_value=tts)
    fake.client.AudioEncoding = MagicMock(LINEAR_PCM=1)
    sys.modules["riva"] = fake
    sys.modules["riva.client"] = fake.client


async def _collect(agen):
    return [f async for f in agen]


async def test_riva_tts_yields_audio_with_context_id():
    _install_fake_riva(b"\x01\x02" * 100)
    from riva_tts import RivaTTSService
    svc = RivaTTSService()
    frames = await _collect(svc.run_tts("hello", "ctx-1"))
    audio = [f for f in frames if isinstance(f, TTSAudioRawFrame)]
    assert audio and audio[0].sample_rate == 22050 and audio[0].num_channels == 1
    assert audio[0].context_id == "ctx-1"


async def test_riva_tts_error_yields_errorframe():
    _install_fake_riva(raise_exc=RuntimeError("tts down"))
    from riva_tts import RivaTTSService
    svc = RivaTTSService()
    frames = await _collect(svc.run_tts("hi", "c"))
    assert any(isinstance(f, ErrorFrame) for f in frames)
