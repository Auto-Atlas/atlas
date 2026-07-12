#
# Chatterbox TTS — pipecat service for a local Chatterbox-TTS-Server
# (github.com/devnen/Chatterbox-TTS-Server, OpenAI-compatible, port 8004).
#
# Subclasses pipecat's OpenAITTSService because the server differs from real
# OpenAI in exactly two ways:
#   1. voices are server-side files ("Emily.wav", or a cloned reference) —
#      OpenAI's VALID_VOICES whitelist must not apply
#   2. it streams wav/opus/mp3, not raw "pcm" — we request wav (24kHz 16-bit
#      mono PCM with a RIFF header) and strip the header off the stream front
#

from typing import AsyncGenerator

from loguru import logger

from pipecat.frames.frames import ErrorFrame, Frame, TTSAudioRawFrame
from pipecat.services.openai.tts import OpenAITTSService


class ChatterboxTTSService(OpenAITTSService):
    async def run_tts(self, text: str, context_id: str) -> AsyncGenerator[Frame, None]:
        logger.debug(f"{self}: Generating TTS [{text}]")
        voice = self._settings.voice
        if not voice:
            yield ErrorFrame(error="Chatterbox TTS voice must be specified")
            return
        try:
            create_params = {
                "input": text,
                "model": self._settings.model,
                "voice": voice,  # server-side voice file, passed verbatim
                "response_format": "wav",
            }
            if self._settings.speed:
                create_params["speed"] = self._settings.speed

            async with self._client.audio.speech.with_streaming_response.create(
                **create_params
            ) as r:
                if r.status_code != 200:
                    error = await r.text()
                    logger.error(
                        f"{self} error getting audio (status: {r.status_code}, error: {error})"
                    )
                    yield ErrorFrame(
                        error=f"Error getting audio (status: {r.status_code}, error: {error})"
                    )
                    return

                await self.start_tts_usage_metrics(text)

                # The first bytes are the RIFF header; the PCM payload starts
                # 8 bytes past the "data" chunk marker. Buffer until the
                # marker clears, then stream raw samples.
                header_done = False
                buf = b""
                async for chunk in r.iter_bytes(self.chunk_size):
                    if not chunk:
                        continue
                    if not header_done:
                        buf += chunk
                        i = buf.find(b"data")
                        if i < 0 or len(buf) < i + 8:
                            continue
                        chunk = buf[i + 8 :]
                        header_done = True
                        if not chunk:
                            continue
                    await self.stop_ttfb_metrics()
                    yield TTSAudioRawFrame(
                        chunk, self.sample_rate, 1, context_id=context_id
                    )
        except Exception as e:
            yield ErrorFrame(error=f"Chatterbox TTS error: {e}")
