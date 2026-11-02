"""Value types shared by the HTTP layer, the mapping and the transcriber backends."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class TranscribeOptions:
    """Per-request decoding options. `language=None` means auto-detect."""

    language: str | None
    initial_prompt: str | None


@dataclass(frozen=True, slots=True)
class RawWord:
    """A word as the recogniser emits it: seconds, untrimmed text."""

    text: str
    start: float
    end: float
    probability: float


@dataclass(frozen=True, slots=True)
class RawTranscript:
    language: str
    duration_seconds: float
    words: tuple[RawWord, ...]


@dataclass(frozen=True, slots=True)
class Word:
    """A word on the wire: integer milliseconds, stripped non-empty text."""

    text: str
    start_ms: int
    end_ms: int
    probability: float


@dataclass(frozen=True, slots=True)
class Transcript:
    language: str
    duration_ms: int
    words: tuple[Word, ...]
