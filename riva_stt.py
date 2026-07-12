"""NVIDIA Riva ASR as a pipecat SegmentedSTTService (Jetson-only backend).

VAD-segmented + offline recognize(): SegmentedSTTService buffers between
VAD start/stop and calls run_stt() once per utterance with WAV-wrapped
bytes — exactly the Whisper contract. `riva.client` is imported lazily so
this module is import-clean on non-Jetson boxes (the import-boundary test
enforces it).
"""
from __future__ import annotations
import asyncio
import os
from typing import AsyncGenerator, TYPE_CHECKING

from loguru import logger
from pipecat.frames.frames import ErrorFrame, Frame, TranscriptionFrame
from pipecat.services.stt_service import SegmentedSTTService
from pipecat.utils.time import time_now_iso8601

if TYPE_CHECKING:  # never imported at runtime on x86
    import riva.client  # noqa: F401


class RivaSTTService(SegmentedSTTService):
    def __init__(self, *, server: str = "localhost:50051",
                 model: str = "conformer-en-US-asr-streaming-asr-bls-ensemble",
                 language: str = "en-US", sample_rate: int = 16000, **kwargs):
        super().__init__(sample_rate=sample_rate, **kwargs)
        self._server = os.getenv("RIVA_SERVER", server)
        self._model = os.getenv("RIVA_ASR_MODEL", model)
        self._language = language
        self._riva_rate = sample_rate
        import riva.client  # function-local: Jetson-only
        self._riva = riva.client
        self._auth = riva.client.Auth(uri=self._server)
        self._asr = riva.client.ASRService(self._auth)

    def _config(self):
        return self._riva.RecognitionConfig(
            encoding=self._riva.AudioEncoding.LINEAR_PCM,
            language_code=self._language,
            max_alternatives=1,
            sample_rate_hertz=self._riva_rate,
            model=self._model,
        )

    async def run_stt(self, audio: bytes) -> AsyncGenerator[Frame, None]:
        try:
            loop = asyncio.get_event_loop()
            resp = await loop.run_in_executor(
                None, lambda: self._asr.offline_recognize(audio, self._config()))
            text = ""
            if resp.results and resp.results[0].alternatives:
                text = resp.results[0].alternatives[0].transcript.strip()
            if text:
                yield TranscriptionFrame(text, self._user_id or "", time_now_iso8601(),
                                         language=self._language, finalized=True)
        except Exception as e:  # never crash the pipeline
            logger.warning(f"Riva ASR failed: {e}")
            yield ErrorFrame(error=f"Riva ASR error: {e}")
