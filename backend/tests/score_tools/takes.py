"""Synthetic voice takes for draft tests: words sung at given 16th (or triplet) positions."""

from __future__ import annotations

import math
import random
from collections.abc import Callable, Sequence

from app.services.voice.line_assembly import TimedWord
from app.services.voice.timings import Timings


def timings(words: Sequence[TimedWord], spans: Sequence[tuple[int, int]] | None = None) -> Timings:
    return Timings(words=tuple(words), spans=tuple(spans) if spans is not None else ((0, len(words)),))


def steady(
    texts: Sequence[str],
    onsets: Sequence[float],
    lengths: Sequence[float],
    *,
    bpm: float = 90,
    per_beat: int = 4,
    start_ms: float = 400,
    jitter_ms: float = 0,
    seed: int = 7,
) -> list[TimedWord]:
    """Word ``i`` starts at ``onsets[i]`` units (``per_beat`` units per quarter) and lasts
    ``lengths[i]`` units; times jittered uniformly by up to ``jitter_ms``."""
    rnd = random.Random(seed)
    unit = 60000.0 / bpm / per_beat
    out: list[TimedWord] = []
    for text, onset, length in zip(texts, onsets, lengths, strict=True):
        start = start_ms + onset * unit + rnd.uniform(-jitter_ms, jitter_ms)
        out.append(TimedWord(text, int(round(start)), int(round(start + length * unit))))
    return out


def warped(
    texts: Sequence[str],
    onsets: Sequence[int],
    lengths: Sequence[int],
    tempo_at: Callable[[float], float],
    *,
    start_ms: float = 250,
    jitter_ms: float = 0,
    seed: int = 11,
) -> list[TimedWord]:
    """Like ``steady`` in sixteenths, but the tempo at beat ``b`` is ``tempo_at(b)`` (rubato)."""
    rnd = random.Random(seed)
    last = max(o + n for o, n in zip(onsets, lengths)) + 1
    times = [0.0]
    for k in range(last):
        times.append(times[-1] + 60000.0 / tempo_at(k / 4) / 4)
    out: list[TimedWord] = []
    for text, onset, length in zip(texts, onsets, lengths, strict=True):
        start = start_ms + times[onset] + rnd.uniform(-jitter_ms, jitter_ms)
        out.append(TimedWord(text, int(round(start)), int(round(start_ms + times[onset + length]))))
    return out


def sine_tempo(base: float, depth: float, period_beats: float = 16) -> Callable[[float], float]:
    return lambda beat: base * (1 + depth * math.sin(2 * math.pi * beat / period_beats))
