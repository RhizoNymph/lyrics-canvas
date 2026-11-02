"""Typed errors. Each request error carries its contract code and HTTP status."""

from __future__ import annotations

from typing import ClassVar


class SttError(Exception):
    """A request failure that maps to `{"error": code, "message": ...}`."""

    code: ClassVar[str]
    status: ClassVar[int]

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class InvalidRequest(SttError):
    code = "invalid_request"
    status = 422


class UndecodableAudio(SttError):
    code = "undecodable_audio"
    status = 400


class AudioTooLarge(SttError):
    code = "audio_too_large"
    status = 413


class ModelNotReady(SttError):
    code = "model_not_ready"
    status = 503


class TranscriptionFailed(SttError):
    code = "transcription_failed"
    status = 500


class ConfigError(Exception):
    """An environment variable has an invalid value; the process must not start."""

    def __init__(self, key: str, value: str, reason: str) -> None:
        super().__init__(f"{key}={value!r}: {reason}")
        self.key = key
        self.value = value
        self.reason = reason
