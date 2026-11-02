"""Async client for the STT service's ``POST /v1/transcribe`` (contract section 1).

Failures become typed errors carrying the backend's contract codes: an empty base URL is
``stt_disabled``; connect errors, timeouts and 503 are ``stt_unavailable``; a 413 is
``audio_too_large``; anything else (other statuses, transport errors, malformed bodies) is
``stt_failed``; a transcript with no words is ``empty_transcript``. Audio bytes and transcript
text are never logged.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Protocol

import httpx
from pydantic import BaseModel, ValidationError

from app.services.voice.errors import (
    AudioTooLargeError,
    EmptyTranscriptError,
    SttDisabledError,
    SttFailedError,
    SttUnavailableError,
)
from app.services.voice.line_assembly import TimedWord

logger = logging.getLogger(__name__)

TRANSCRIBE_PATH = "/v1/transcribe"

_EXTENSIONS = {
    "audio/webm": "webm",
    "audio/ogg": "ogg",
    "audio/mp4": "m4a",
    "audio/aac": "aac",
    "audio/mpeg": "mp3",
    "audio/wav": "wav",
    "audio/x-wav": "wav",
}


@dataclass(frozen=True, slots=True)
class Transcript:
    language: str | None
    duration_ms: int | None
    words: tuple[TimedWord, ...]  # non-empty, text trimmed and non-blank, in service order


class Transcriber(Protocol):
    async def transcribe(self, audio: bytes, mime_type: str) -> Transcript: ...


class _SttWord(BaseModel):
    text: str
    start_ms: int
    end_ms: int
    probability: float | None = None


class _SttResponse(BaseModel):
    language: str | None = None
    duration_ms: int | None = None
    words: list[_SttWord]


class _SttErrorBody(BaseModel):
    error: str
    message: str = ""


def _service_error(response: httpx.Response) -> str:
    """The service's error code for messages, or the bare status."""
    try:
        body = _SttErrorBody.model_validate_json(response.content)
    except ValidationError:
        return f"status {response.status_code}"
    return f"status {response.status_code} {body.error}"


def audio_filename(mime_type: str) -> str:
    base = mime_type.split(";", 1)[0].strip().lower()
    return f"take.{_EXTENSIONS.get(base, 'bin')}"


class SttClient:
    """One request per call; ``transport`` lets tests substitute an in-process fake."""

    def __init__(self, base_url: str, timeout_seconds: float, transport: httpx.AsyncBaseTransport | None = None):
        self.base_url = base_url.strip().rstrip("/")
        self.timeout_seconds = timeout_seconds
        self._transport = transport

    @property
    def enabled(self) -> bool:
        return bool(self.base_url)

    async def transcribe(self, audio: bytes, mime_type: str) -> Transcript:
        if not self.enabled:
            raise SttDisabledError("voice capture is not configured on this server")
        url = self.base_url + TRANSCRIBE_PATH
        started = time.perf_counter()
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds, transport=self._transport) as client:
                response = await client.post(url, files={"audio": (audio_filename(mime_type), audio, mime_type)})
        except (httpx.TimeoutException, httpx.ConnectError) as exc:
            logger.warning("stt.unavailable url=%s error_type=%s", url, type(exc).__name__)
            raise SttUnavailableError("the transcription service is not reachable") from exc
        except (httpx.TransportError, httpx.InvalidURL) as exc:
            logger.error("stt.transport_failed url=%s error_type=%s", url, type(exc).__name__)
            raise SttFailedError("the transcription service connection failed") from exc
        elapsed_ms = round((time.perf_counter() - started) * 1000)

        match response.status_code:
            case 200:
                pass
            case 503:
                logger.warning("stt.not_ready url=%s elapsed_ms=%d", url, elapsed_ms)
                raise SttUnavailableError("the transcription service is not ready yet")
            case 413:
                raise AudioTooLargeError("the transcription service rejected the audio as too large")
            case _:
                detail = _service_error(response)
                logger.error("stt.failed url=%s %s elapsed_ms=%d", url, detail, elapsed_ms)
                raise SttFailedError(f"the transcription service failed ({detail})")

        try:
            parsed = _SttResponse.model_validate_json(response.content)
        except ValidationError as exc:
            logger.error("stt.bad_response url=%s errors=%d elapsed_ms=%d", url, exc.error_count(), elapsed_ms)
            raise SttFailedError("the transcription service returned an unreadable response") from exc

        words = tuple(
            TimedWord(text=w.text.strip(), start_ms=w.start_ms, end_ms=w.end_ms) for w in parsed.words if w.text.strip()
        )
        logger.info(
            "stt.transcribed bytes=%d words=%d language=%s duration_ms=%s elapsed_ms=%d",
            len(audio), len(words), parsed.language, parsed.duration_ms, elapsed_ms,
        )
        if not words:
            raise EmptyTranscriptError("no speech was recognised in the recording")
        return Transcript(language=parsed.language, duration_ms=parsed.duration_ms, words=words)
