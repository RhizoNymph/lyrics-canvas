"""A compact, human-readable rendering of a snippet's score (its vocal cadence) for reads.

One header line (meter, tempo, pickup, snap, later meter and tempo changes) and one line per grid
line: each placed syllable as ``syllable@beat~length`` grouped by bar, accents as ``>`` / ``>>``, and
the syllables without a note. Beats and lengths are in beat units of the meter in force (the
denominator note: a quarter in x/4, an eighth in x/8), 1-based within the bar; bars are 1-based and
``pickup`` is the bar before bar 1, counted as the tail of a full bar (an upbeat on 4 is ``@4``).
Example::

    4/4, 90 bpm, snap 1/16
    1. "I _ go now": bar 1: i@1~0.5> go@2.5~0.5> now@3~1>>
    2. "hello world": bar 2: hel@1~0.5 world@4~1+1/3  (unplaced: lo)
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from fractions import Fraction
from typing import Any

from lcxlib.score_ref import PICKUP_BAR, Score, ScoreInvalid, bar_at_tick, beat_unit, check_score

ACCENTS = {0: "", 1: ">", 2: ">>"}


def _number(value: Fraction) -> str:
    """Exact: ``2``, ``1.5``, ``1.125`` for binary fractions, ``1+1/3`` / ``2/3`` otherwise."""
    if value.denominator == 1:
        return str(value.numerator)
    if value.denominator & (value.denominator - 1) == 0:
        return str(float(value))  # exact: ticks are integers and every binary fraction here is small
    whole, rest = divmod(value.numerator, value.denominator)
    part = f"{rest}/{value.denominator}"
    return f"{whole}+{part}" if whole else part


def _bpm(bpm: float | int) -> str:
    return f"{bpm:g}"


def header(score: Score) -> str:
    first_meter, first_tempo = score.meters[0], score.tempos[0]
    parts = [f"{first_meter.num}/{first_meter.den}", f"{_bpm(first_tempo.bpm)} bpm"]
    if score.pickup:
        parts.append(f"pickup {_number(Fraction(score.pickup, first_meter.unit))} beat")
    parts.append(f"snap {score.snap.value}")
    changes = [*((m.bar, f"{m.num}/{m.den}") for m in score.meters[1:]),
               *((t.bar, f"{_bpm(t.bpm)} bpm") for t in score.tempos[1:])]
    text = ", ".join(parts)
    if changes:
        text += "; " + "; ".join(f"bar {bar + 1}: {what}" for bar, what in sorted(changes, key=lambda c: c[0]))
    return text


def _syllables(line: Mapping[str, Any]) -> list[str]:
    cells = line.get("cells") or []
    return [str(c.get("syllable") or "?") for c in cells if isinstance(c, Mapping) and c.get("rest") is not True]


def _bar_label(bar: int) -> str:
    return "pickup" if bar == PICKUP_BAR else f"bar {bar + 1}"


def line_cadence(score: Score, index: int, grid_line: Mapping[str, Any]) -> str:
    syllables = _syllables(grid_line)
    notes = score.lines[index].notes
    label = f'{index + 1}. "{grid_line.get("raw_text", "")}":'
    if not syllables:
        return f"{label} (rests only)"
    groups: list[tuple[int, list[str]]] = []
    for note in notes:
        position = bar_at_tick(score, note.t)
        unit = beat_unit(score, note.t)
        offset = position.offset
        if position.bar == PICKUP_BAR:  # the pickup is the tail of a full bar of the first meter
            offset += score.meters[0].bar_length - score.pickup
        piece = (f"{syllables[note.s]}@{_number(1 + Fraction(offset, unit))}"
                 f"~{_number(Fraction(note.d, unit))}{ACCENTS.get(note.a, '')}")
        if groups and groups[-1][0] == position.bar:
            groups[-1][1].append(piece)
        else:
            groups.append((position.bar, [piece]))
    placed = {note.s for note in notes}
    unplaced = [syl for k, syl in enumerate(syllables) if k not in placed]
    body = " | ".join(f"{_bar_label(bar)}: {' '.join(pieces)}" for bar, pieces in groups) or "(no notes)"
    return f"{label} {body}" + (f"  (unplaced: {', '.join(unplaced)})" if unplaced and notes else "")


def cadence(raw_score: object, grid: Mapping[str, Any] | None) -> list[str] | None:
    """The rendering of a stored score, None without one. A score that does not fit its grid (never
    stored by the server) renders as one line naming the reason."""
    if raw_score is None:
        return None
    try:
        score = check_score(raw_score, grid)
    except ScoreInvalid as exc:
        return [f"(score does not fit the grid: {exc.reason.value})"]
    lines: Sequence[Mapping[str, Any]] = list((grid or {}).get("lines") or [])
    return [header(score), *(line_cadence(score, i, line) for i, line in enumerate(lines))]
