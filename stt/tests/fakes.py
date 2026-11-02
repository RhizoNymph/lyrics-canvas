"""Test doubles behind the Transcriber protocol: no model, no GPU."""

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from typing import BinaryIO

from stt_service.domain import RawTranscript, RawWord, TranscribeOptions
from stt_service.transcriber import Transcriber

LANGUAGES = frozenset({"en", "de", "fr", "es"})


@dataclass
class Call:
    audio: bytes
    options: TranscribeOptions


@dataclass
class FakeTranscriber:
    """Returns a canned transcript (or raises) and records what it was asked."""

    result: RawTranscript | None = None
    error: BaseException | None = None
    languages: frozenset[str] = LANGUAGES
    calls: list[Call] = field(default_factory=list)

    def transcribe(self, audio: BinaryIO, options: TranscribeOptions) -> RawTranscript:
        self.calls.append(Call(audio.read(), options))
        if self.error is not None:
            raise self.error
        assert self.result is not None, "FakeTranscriber needs a result or an error"
        return self.result


def raw(
    *words: tuple[str, float, float, float], language: str = "en", duration: float = 2.0
) -> RawTranscript:
    return RawTranscript(
        language=language,
        duration_seconds=duration,
        words=tuple(RawWord(text=t, start=s, end=e, probability=p) for t, s, e, p in words),
    )


class GatedLoader:
    """Loader that blocks until released, then returns (or raises) per attempt."""

    def __init__(self, outcomes: list[Transcriber | BaseException]) -> None:
        self._outcomes = list(outcomes)
        self.release = threading.Event()
        self.attempts = 0

    def __call__(self) -> Transcriber:
        self.release.wait(timeout=10)
        self.attempts += 1
        outcome = self._outcomes.pop(0) if len(self._outcomes) > 1 else self._outcomes[0]
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome


def instant_loader(transcriber: Transcriber):
    def load() -> Transcriber:
        return transcriber

    return load
