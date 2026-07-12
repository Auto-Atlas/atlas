import numpy as np
import pytest
import speaker_state
import speaker_stt
from speaker_id import Profile


class _FakeBase:
    """Stands in for WhisperSTTService.run_stt (an async generator)."""
    async def run_stt(self, audio):
        yield "FRAME"


class _Probe(speaker_stt.SpeakerIDMixin, _FakeBase):
    def __init__(self, profiles, emb):
        self._profiles = profiles
        self._embed_fn = lambda audio: emb        # inject; no real encoder
        self._reprompt_owner = False


def setup_function():
    speaker_state.reset()


@pytest.mark.asyncio
async def test_identifies_owner_and_passes_frames(monkeypatch):
    monkeypatch.setenv("EVE_SPEAKER_THRESHOLD", "0.75")
    will = Profile("Owner", "owner", np.array([1.0, 0.0, 0.0], np.float32))
    probe = _Probe([will], np.array([1.0, 0.0, 0.0], np.float32))
    frames = [f async for f in probe.run_stt(b"WAVDATA")]
    assert frames == ["FRAME"]                      # delegation intact
    assert speaker_state.current_tier() == "owner"


@pytest.mark.asyncio
async def test_enroll_capture_dumps_wav_when_dir_set(monkeypatch, tmp_path):
    cap = tmp_path / "enroll"
    monkeypatch.setenv("EVE_ENROLL_CAPTURE_DIR", str(cap))
    will = Profile("Owner", "owner", np.array([1.0, 0.0, 0.0], np.float32))
    probe = _Probe([will], np.array([1.0, 0.0, 0.0], np.float32))
    _ = [f async for f in probe.run_stt(b"WAVDATA")]
    dumped = list(cap.glob("*.wav"))
    assert len(dumped) == 1 and dumped[0].read_bytes() == b"WAVDATA"


@pytest.mark.asyncio
async def test_no_capture_when_dir_unset(monkeypatch, tmp_path):
    monkeypatch.delenv("EVE_ENROLL_CAPTURE_DIR", raising=False)
    will = Profile("Owner", "owner", np.array([1.0, 0.0, 0.0], np.float32))
    probe = _Probe([will], np.array([1.0, 0.0, 0.0], np.float32))
    _ = [f async for f in probe.run_stt(b"WAVDATA")]   # must not raise / write anywhere


class _FrameWithText:
    def __init__(self, text):
        self.text = text


class _BaseWithText:
    """run_stt that yields a TranscriptionFrame-like object carrying .text."""
    text_to_yield = "whiskey tango foxtrot, make an invoice"

    async def run_stt(self, audio):
        yield _FrameWithText(self.text_to_yield)


class _PhraseProbe(speaker_stt.SpeakerIDMixin, _BaseWithText):
    def __init__(self, profiles, emb, text):
        self._profiles = profiles
        self._embed_fn = lambda audio: emb
        self.text_to_yield = text


@pytest.mark.asyncio
async def test_owner_phrase_grants_override_despite_low_voice(monkeypatch):
    monkeypatch.setenv("EVE_OWNER_PHRASE", "whiskey tango foxtrot")
    monkeypatch.setenv("EVE_SPEAKER_THRESHOLD", "0.75")
    will = Profile("Owner", "owner", np.array([1.0, 0.0, 0.0], np.float32))
    # voice scores as a stranger (orthogonal) -> would be 'unknown'...
    probe = _PhraseProbe([will], np.array([0.0, 0.0, 1.0], np.float32),
                         "Whiskey, Tango, Foxtrot — make an invoice")
    _ = [f async for f in probe.run_stt(b"WAV")]
    assert speaker_state.current_tier() == "owner"   # ...but the phrase overrides


@pytest.mark.asyncio
async def test_no_phrase_no_override(monkeypatch):
    monkeypatch.setenv("EVE_OWNER_PHRASE", "whiskey tango foxtrot")
    monkeypatch.setenv("EVE_SPEAKER_THRESHOLD", "0.75")
    will = Profile("Owner", "owner", np.array([1.0, 0.0, 0.0], np.float32))
    probe = _PhraseProbe([will], np.array([0.0, 0.0, 1.0], np.float32),
                         "make an invoice please")     # no code word
    _ = [f async for f in probe.run_stt(b"WAV")]
    assert speaker_state.current_tier() == "unknown"  # stays fail-closed


@pytest.mark.asyncio
async def test_unknown_voice_is_logged(monkeypatch, tmp_path):
    log = tmp_path / "unknown.log"
    monkeypatch.setenv("EVE_UNKNOWN_LOG", str(log))
    monkeypatch.setenv("EVE_SPEAKER_THRESHOLD", "0.75")
    will = Profile("Owner", "owner", np.array([1.0, 0.0, 0.0], np.float32))
    probe = _Probe([will], np.array([0.0, 0.0, 1.0], np.float32))   # orthogonal -> unknown
    _ = [f async for f in probe.run_stt(b"WAVDATA")]
    assert speaker_state.current_tier() == "unknown"
    assert log.is_file() and log.read_text().strip()   # something was written down
