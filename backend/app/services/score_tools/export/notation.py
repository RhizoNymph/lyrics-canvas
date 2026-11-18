"""Measures and notated values for the MusicXML writer (docs/features/score_draft_export.md#musicxml).

Every note and every gap is cut at barlines, and each cut part is written as notated values: the
largest straight or dotted values first, plus at most one triplet value when the length is not a
multiple of 3 ticks (a 64th). Parts of one note are tied.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.services.score import Meter, Score, bar_count, bar_start_tick
from app.services.score.timing import meter_at_bar


@dataclass(frozen=True, slots=True)
class NoteValue:
    name: str  # MusicXML <type>
    dots: int
    triplet: bool  # 3 in the time of 2
    ticks: int


STRAIGHT_VALUES: tuple[NoteValue, ...] = (
    NoteValue("whole", 1, False, 288),
    NoteValue("whole", 0, False, 192),
    NoteValue("half", 1, False, 144),
    NoteValue("half", 0, False, 96),
    NoteValue("quarter", 1, False, 72),
    NoteValue("quarter", 0, False, 48),
    NoteValue("eighth", 1, False, 36),
    NoteValue("eighth", 0, False, 24),
    NoteValue("16th", 1, False, 18),
    NoteValue("16th", 0, False, 12),
    NoteValue("32nd", 1, False, 9),
    NoteValue("32nd", 0, False, 6),
    NoteValue("64th", 0, False, 3),
)

TRIPLET_VALUES: tuple[NoteValue, ...] = (
    NoteValue("half", 0, True, 64),
    NoteValue("quarter", 0, True, 32),
    NoteValue("eighth", 0, True, 16),
    NoteValue("16th", 0, True, 8),
    NoteValue("32nd", 0, True, 4),
    NoteValue("64th", 0, True, 2),
    NoteValue("128th", 0, True, 1),
)


def decompose(ticks: int) -> list[NoteValue]:
    """Values summing to ``ticks`` (>= 1): straight/dotted greedily, then one triplet value with the
    remainder mod 3 (the largest such that fits)."""
    if ticks < 1:
        raise ValueError(f"cannot notate {ticks} ticks")
    triplet: NoteValue | None = None
    if ticks % 3:
        triplet = next(v for v in TRIPLET_VALUES if v.ticks <= ticks and v.ticks % 3 == ticks % 3)
    rest = ticks - (triplet.ticks if triplet else 0)
    out: list[NoteValue] = []
    for value in STRAIGHT_VALUES:
        while rest >= value.ticks:
            out.append(value)
            rest -= value.ticks
    if triplet is not None:
        out.append(triplet)
    return out


@dataclass(frozen=True, slots=True)
class Piece:
    """One written note or rest inside a measure."""

    start: int  # absolute tick
    value: NoteValue
    note: int | None  # index into score.all_notes(); None for a rest
    tie_start: bool  # a later piece of the same note follows
    tie_stop: bool  # an earlier piece of the same note precedes


@dataclass(frozen=True, slots=True)
class Measure:
    number: int  # 0 for the pickup, else bar + 1
    implicit: bool  # the pickup measure
    start: int
    length: int
    meter: Meter | None  # time signature shown at this measure
    bpm: int | float | None  # tempo shown at this measure
    pieces: tuple[Piece, ...]  # empty for a measure that is one whole-measure rest


def _fill(start: int, end: int, note: int | None, first: bool, last: bool) -> list[Piece]:
    values = decompose(end - start)
    out: list[Piece] = []
    cursor = start
    for index, value in enumerate(values):
        is_first, is_last = index == 0, index == len(values) - 1
        out.append(
            Piece(
                start=cursor,
                value=value,
                note=note,
                tie_start=note is not None and not (is_last and last),
                tie_stop=note is not None and not (is_first and first),
            )
        )
        cursor += value.ticks
    return out


def _pieces(spans: list[tuple[int, int]], start: int, end: int) -> tuple[Piece, ...]:
    """Pieces of the measure ``[start, end)`` from the notes' ``(t, end)`` spans."""
    out: list[Piece] = []
    cursor = start
    for index, (t, stop) in enumerate(spans):
        if stop <= start or t >= end:
            continue
        lo, hi = max(t, start), min(stop, end)
        if lo > cursor:
            out.extend(_fill(cursor, lo, None, True, True))
        out.extend(_fill(lo, hi, index, lo == t, hi == stop))
        cursor = hi
    if not out:
        return ()
    if cursor < end:
        out.extend(_fill(cursor, end, None, True, True))
    return tuple(out)


def measures(score: Score) -> list[Measure]:
    """The pickup measure (when ``pickup > 0``) and bars ``0 .. bar_count - 1``."""
    spans = [(note.t, note.end) for note in score.all_notes()]
    meter_bars = {meter.bar: meter for meter in score.meters}
    tempo_bars = {tempo.bar: tempo.bpm for tempo in score.tempos}
    out: list[Measure] = []
    if score.pickup > 0:
        out.append(
            Measure(0, True, 0, score.pickup, score.meters[0], score.tempos[0].bpm, _pieces(spans, 0, score.pickup))
        )
    for bar in range(bar_count(score)):
        start = bar_start_tick(score, bar)
        length = meter_at_bar(score, bar).bar_length
        first = not out
        meter = score.meters[0] if first else (meter_bars.get(bar) if bar > 0 else None)
        bpm = score.tempos[0].bpm if first else (tempo_bars.get(bar) if bar > 0 else None)
        out.append(Measure(bar + 1, False, start, length, meter, bpm, _pieces(spans, start, start + length)))
    return out
