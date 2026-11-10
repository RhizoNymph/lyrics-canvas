"""Typed voice-take failures. Each carries the wire error code and HTTP status from the contract;
the API turns them into ``HTTPException(status, {"error": code, "message": message})``."""

from __future__ import annotations

from enum import StrEnum
from typing import ClassVar


class VoiceErrorCode(StrEnum):
    canvas_not_found = "canvas_not_found"
    snippet_not_found = "snippet_not_found"
    recording_not_found = "recording_not_found"
    audio_too_large = "audio_too_large"
    unsupported_audio_type = "unsupported_audio_type"
    invalid_meta = "invalid_meta"
    empty_transcript = "empty_transcript"
    stt_disabled = "stt_disabled"
    stt_unavailable = "stt_unavailable"
    stt_failed = "stt_failed"
    persist_failed = "persist_failed"
    invalid_verification = "invalid_verification"
    export_too_large = "export_too_large"


class VoiceTakeError(Exception):
    code: ClassVar[VoiceErrorCode]
    status: ClassVar[int]

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


class CanvasNotFoundError(VoiceTakeError):
    code = VoiceErrorCode.canvas_not_found
    status = 404


class SnippetNotFoundError(VoiceTakeError):
    code = VoiceErrorCode.snippet_not_found
    status = 404


class RecordingNotFoundError(VoiceTakeError):
    code = VoiceErrorCode.recording_not_found
    status = 404


class AudioTooLargeError(VoiceTakeError):
    code = VoiceErrorCode.audio_too_large
    status = 413


class UnsupportedAudioTypeError(VoiceTakeError):
    code = VoiceErrorCode.unsupported_audio_type
    status = 415


class InvalidMetaError(VoiceTakeError):
    code = VoiceErrorCode.invalid_meta
    status = 422


class EmptyTranscriptError(VoiceTakeError):
    code = VoiceErrorCode.empty_transcript
    status = 422


class SttError(VoiceTakeError):
    """Base for failures talking to the STT service."""


class SttDisabledError(SttError):
    code = VoiceErrorCode.stt_disabled
    status = 503


class SttUnavailableError(SttError):
    code = VoiceErrorCode.stt_unavailable
    status = 503


class SttFailedError(SttError):
    code = VoiceErrorCode.stt_failed
    status = 502


class VoicePersistError(VoiceTakeError):
    """Storing the take failed; the transaction was rolled back and nothing changed."""

    code = VoiceErrorCode.persist_failed
    status = 500


class InvalidVerificationError(VoiceTakeError):
    """The checked transcript is empty or too long after normalizing."""

    code = VoiceErrorCode.invalid_verification
    status = 422


class ExportTooLargeError(VoiceTakeError):
    """The dataset export would hold more audio than VOICE_EXPORT_MAX_BYTES."""

    code = VoiceErrorCode.export_too_large
    status = 413
