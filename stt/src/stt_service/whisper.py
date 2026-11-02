"""faster-whisper backend for the Transcriber protocol."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, BinaryIO

import av
import numpy as np
from faster_whisper.audio import decode_audio

from .config import Settings
from .domain import RawTranscript, RawWord, TranscribeOptions
from .errors import UndecodableAudio
from .logs import log_event
from .model_files import resolve_model
from .transcriber import ModelLoader, Transcriber

if TYPE_CHECKING:
    from faster_whisper import WhisperModel

logger = logging.getLogger("stt.whisper")

SAMPLE_RATE = 16000
# Language reported for audio that decodes to no samples (ISO 639-2 "undetermined").
UNDETERMINED = "und"


def decode(audio: BinaryIO) -> np.ndarray:
    """Decode any container/codec PyAV understands to 16 kHz mono float32."""
    try:
        samples = decode_audio(audio, sampling_rate=SAMPLE_RATE)
    except av.error.FFmpegError as exc:
        raise UndecodableAudio(f"could not decode audio: {exc}") from exc
    except (IndexError, ValueError) as exc:
        # No audio stream (IndexError) or no decodable frames (empty concatenate).
        raise UndecodableAudio(f"could not decode audio: {type(exc).__name__}: {exc}") from exc
    return np.asarray(samples, dtype=np.float32)


class WhisperTranscriber:
    def __init__(self, model: WhisperModel, beam_size: int) -> None:
        self._model = model
        self._beam_size = beam_size
        self._languages = frozenset(model.supported_languages)

    @property
    def languages(self) -> frozenset[str]:
        return self._languages

    def transcribe(self, audio: BinaryIO, options: TranscribeOptions) -> RawTranscript:
        samples = decode(audio)
        if samples.size == 0:
            return RawTranscript(language=options.language or UNDETERMINED, duration_seconds=0.0, words=())
        segments, info = self._model.transcribe(
            samples,
            language=options.language,
            initial_prompt=options.initial_prompt,
            beam_size=self._beam_size,
            word_timestamps=True,
            vad_filter=True,
        )
        # `segments` is lazy: recognition happens while it is consumed, here,
        # inside the worker thread.
        words = tuple(
            RawWord(text=w.word, start=w.start, end=w.end, probability=w.probability)
            for segment in segments
            for w in (segment.words or ())
        )
        return RawTranscript(language=info.language, duration_seconds=info.duration, words=words)


def whisper_loader(settings: Settings) -> ModelLoader:
    def load() -> Transcriber:
        from faster_whisper import WhisperModel

        resolved = resolve_model(settings.model, settings.model_cache_dir, offline=settings.offline)
        log_event(
            logger,
            logging.INFO,
            "model.files",
            model=settings.model,
            path=resolved.path,
            downloaded=resolved.downloaded,
            offline=settings.offline,
        )
        model = WhisperModel(
            resolved.path,
            device=settings.device.value,
            compute_type=settings.compute_type,
            cpu_threads=settings.cpu_threads,
            num_workers=1,
        )
        return WhisperTranscriber(model, settings.beam_size)

    return load
