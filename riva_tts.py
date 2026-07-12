"""NVIDIA Riva TTS as a pipecat TTSService (Jetson-only backend).

run_tts(text, context_id) threads context_id into every TTSAudioRawFrame
(mirrors chatterbox_tts). `riva.client` is imported lazily so this module
is import-clean on non-Jetson boxes.
"""
from __future__ import annotations
import asyncio
import os
from typing import AsyncGenerator, TYPE_CHECKING

from loguru import logger
from pipecat.frames.frames import ErrorFrame, Frame, TTSAudioRawFrame
from pipecat.services.tts_service import TTSService

if TYPE_CHECKING:
    import riva.client  # noqa: F401


class RivaTTSService(TTSService):
    def __init__(self, *, server: str = "localhost:50051", voice: str | None = None,
                 language: str = "en-US", sample_rate: int = 22050, **kwargs):
        super().__init__(sample_rate=sample_rate, **kwargs)
        self._server = os.getenv("RIVA_SERVER", server)
        self._voice = os.getenv("RIVA_TTS_VOICE") or voice  # None => Riva default
        self._language = language
        self._riva_rate = sample_rate
        import riva.client  # function-local: Jetson-only
        self._riva = riva.client
        self._auth = riva.client.Auth(uri=self._server)
        self._tts = riva.client.SpeechSynthesisService(self._auth)

    def can_generate_metrics(self) -> bool:
        return True

    async def run_tts(self, text: str, context_id: str) -> AsyncGenerator[Frame, None]:
        try:
            await self.start_ttfb_metrics()
            loop = asyncio.get_event_loop()
            resp = await loop.run_in_executor(None, lambda: self._tts.synthesize(
                text, voice_name=self._voice, language_code=self._language,
                encoding=self._riva.AudioEncoding.LINEAR_PCM, sample_rate_hz=self._riva_rate))
            await self.stop_ttfb_metrics()
            await self.start_tts_usage_metrics(text)
            audio = getattr(resp, "audio", b"")
            if audio:
                yield TTSAudioRawFrame(audio, self._riva_rate, 1, context_id=context_id)
        except Exception as e:
            logger.warning(f"Riva TTS failed: {e}")
            yield ErrorFrame(error=f"Riva TTS error: {e}")
