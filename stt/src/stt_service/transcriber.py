"""The seam between the HTTP layer and a speech recogniser."""

from __future__ import annotations

from collections.abc import Callable
from typing import BinaryIO, Protocol

from .domain import RawTranscript, TranscribeOptions


class Transcriber(Protocol):
    """A loaded recogniser. Calls are blocking and made from a worker thread,
    one at a time.

    `transcribe` raises `UndecodableAudio` when the input cannot be decoded and
    `TranscriptionFailed` (or any other exception, which the caller wraps) when
    recognition itself fails.
    """

    @property
    def languages(self) -> frozenset[str]:
        """Language codes accepted in `TranscribeOptions.language`."""
        ...

    def transcribe(self, audio: BinaryIO, options: TranscribeOptions) -> RawTranscript: ...


# Builds a Transcriber; blocking (downloads and loads weights). Raises on failure.
ModelLoader = Callable[[], Transcriber]
