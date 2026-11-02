"""Recogniser output (seconds, raw text) -> wire transcript (ms, stripped, ordered)."""

from __future__ import annotations

from .domain import RawTranscript, RawWord, Transcript, Word


def seconds_to_ms(seconds: float) -> int:
    return max(0, round(seconds * 1000))


def _word(raw: RawWord) -> Word | None:
    text = raw.text.strip()
    if not text:
        return None
    start_ms = seconds_to_ms(raw.start)
    end_ms = max(start_ms, seconds_to_ms(raw.end))
    probability = min(1.0, max(0.0, raw.probability))
    return Word(text=text, start_ms=start_ms, end_ms=end_ms, probability=probability)


def to_transcript(raw: RawTranscript) -> Transcript:
    words = [w for w in (_word(r) for r in raw.words) if w is not None]
    # Whisper emits words in order; a stable sort guards against segment
    # overlap without reordering words that start together.
    words.sort(key=lambda w: w.start_ms)
    return Transcript(
        language=raw.language,
        duration_ms=seconds_to_ms(raw.duration_seconds),
        words=tuple(words),
    )
