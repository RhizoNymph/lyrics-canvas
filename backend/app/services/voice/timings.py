"""A recording's STT words and the line spans behind its lines: typed form, invariants, JSON. Pure.

Stored on ``snippet_recordings.words`` / ``line_spans`` (jsonb, docs/features/voice_dataset.md):

- ``words``: ``[{"text", "start_ms", "end_ms", "probability": float | null}]`` in service order;
- ``line_spans``: per line ``[start, end)`` indices into ``words``. Spans are contiguous,
  non-overlapping and cover the words in order; a blank line has ``[i, i)``.

``Timings`` cannot be built with spans that break those rules (``TimingsError``).
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from pydantic import BaseModel, TypeAdapter, ValidationError

from app.services.voice.line_assembly import AssembledLines, TimedWord, WordSpan


class TimingsError(Exception):
    """Spans that do not tile the words, or stored JSON that is not the documented shape."""


@dataclass(frozen=True, slots=True)
class LineTiming:
    word_start: int
    word_end: int
    start_ms: int | None  # None for a blank line
    end_ms: int | None


@dataclass(frozen=True, slots=True)
class Timings:
    words: tuple[TimedWord, ...]
    spans: tuple[WordSpan, ...]

    def __post_init__(self) -> None:
        problem = span_problem(self.spans, len(self.words))
        if problem is not None:
            raise TimingsError(problem)

    @classmethod
    def of(cls, assembled: AssembledLines) -> Timings:
        return cls(words=assembled.words, spans=assembled.spans)

    def line_timings(self) -> tuple[LineTiming, ...]:
        out: list[LineTiming] = []
        for start, end in self.spans:
            if start == end:
                out.append(LineTiming(start, end, None, None))
            else:
                out.append(LineTiming(start, end, self.words[start].start_ms, self.words[end - 1].end_ms))
        return tuple(out)


def span_problem(spans: Sequence[WordSpan], word_count: int) -> str | None:
    """Why ``spans`` do not tile ``word_count`` words in order (None when they do)."""
    if not spans:
        return "at least one line span is required"
    expected = 0
    for index, (start, end) in enumerate(spans):
        if start != expected:
            return f"span {index} starts at {start}, expected {expected}"
        if end < start:
            return f"span {index} ends before it starts"
        expected = end
    if expected != word_count:
        return f"spans cover {expected} words, the take has {word_count}"
    return None


# ---- JSON ----


class _StoredWord(BaseModel):
    text: str
    start_ms: int
    end_ms: int
    probability: float | None = None


_WORDS = TypeAdapter(list[_StoredWord])
_SPANS = TypeAdapter(list[tuple[int, int]])


def words_json(words: Sequence[TimedWord]) -> list[dict[str, object]]:
    return [
        {"text": w.text, "start_ms": w.start_ms, "end_ms": w.end_ms, "probability": w.probability} for w in words
    ]


def spans_json(spans: Sequence[WordSpan]) -> list[list[int]]:
    return [[start, end] for start, end in spans]


def parse_words(raw: object) -> tuple[TimedWord, ...]:
    try:
        parsed = _WORDS.validate_python(raw)
    except ValidationError as exc:
        raise TimingsError(f"stored words are malformed ({exc.error_count()} errors)") from exc
    return tuple(TimedWord(w.text, w.start_ms, w.end_ms, probability=w.probability) for w in parsed)


def parse_spans(raw: object) -> tuple[WordSpan, ...]:
    try:
        return tuple(_SPANS.validate_python(raw))
    except ValidationError as exc:
        raise TimingsError(f"stored line spans are malformed ({exc.error_count()} errors)") from exc


def stored_timings(words: object | None, spans: object | None) -> Timings | None:
    """The row's timings, or None when either column is null. Malformed data raises TimingsError."""
    if words is None or spans is None:
        return None
    return Timings(words=parse_words(words), spans=parse_spans(spans))
