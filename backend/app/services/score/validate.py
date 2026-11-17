"""validate: JSON -> typed Score or the first failure (spec/score_format.md section 4).

``check_score`` raises ``ScoreInvalid``; ``validate`` returns a result instead. The order of the
checks is normative: every port reports the same first failure.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from app.services.score.codec import score_to_json
from app.services.score.constants import (
    BPM_DECIMALS_TOLERANCE,
    BPM_MAX,
    BPM_MIN,
    DENS,
    LINE_KEYS,
    MAX_BAR,
    MAX_METERS,
    MAX_SAFE_INT,
    MAX_TEMPOS,
    MAX_TICKS,
    METER_KEYS,
    NOTE_KEYS,
    NUM_MAX,
    NUM_MIN,
    PPQ,
    SCORE_KEYS,
    TEMPO_KEYS,
    VERSION,
    ACCENTS,
)
from app.services.score.errors import ScoreInvalid, ScoreReason
from app.services.score.grid_view import syllable_counts
from app.services.score.types import GridJson, Meter, Note, Score, ScoreLine, Snap, Tempo

R = ScoreReason


def as_integer(value: object) -> int | None:
    """The integer a JSON value denotes, or None (spec section 2, Integers)."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if abs(value) <= MAX_SAFE_INT else None
    if isinstance(value, float) and math.isfinite(value) and value.is_integer() and abs(value) <= MAX_SAFE_INT:
        return int(value)
    return None


def as_number(value: object) -> int | float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and math.isfinite(value):
        return value
    return None


def is_valid_meter_value(num: int, den: int) -> bool:
    return NUM_MIN <= num <= NUM_MAX and den in DENS


def is_valid_bpm(bpm: int | float) -> bool:
    if bpm < BPM_MIN or bpm > BPM_MAX:
        return False
    scaled = bpm * 100
    return abs(scaled - round(scaled)) <= BPM_DECIMALS_TOLERANCE


def _keys_exactly(value: object, keys: Sequence[str]) -> Mapping[str, Any] | None:
    if not isinstance(value, Mapping) or set(value) != set(keys):
        return None
    return value


def _integers(entry: Mapping[str, Any], keys: Sequence[str]) -> list[int] | None:
    out: list[int] = []
    for key in keys:
        number = as_integer(entry.get(key))
        if number is None:
            return None
        out.append(number)
    return out


def _check_bar(bar: int, index: int, previous: int | None, first: ScoreReason, order: ScoreReason, what: str) -> None:
    if index == 0 and bar != 0:
        raise ScoreInvalid(first, f"the first {what} must be at bar 0, got {bar}")
    if previous is not None and bar <= previous:
        raise ScoreInvalid(order, f"{what} {index} at bar {bar} is not after bar {previous}")
    if bar > MAX_BAR:
        raise ScoreInvalid(R.bar_out_of_range, f"{what} {index} at bar {bar} is past bar {MAX_BAR}")


def _meters(value: object) -> tuple[Meter, ...]:
    if not isinstance(value, list):
        raise ScoreInvalid(R.malformed, "meters must be an array")
    if not value:
        raise ScoreInvalid(R.meters_empty, "meters must have an entry at bar 0")
    if len(value) > MAX_METERS:
        raise ScoreInvalid(R.too_many_meters, f"at most {MAX_METERS} meters, got {len(value)}")
    meters: list[Meter] = []
    for index, raw in enumerate(value):
        entry = _keys_exactly(raw, METER_KEYS)
        numbers = None if entry is None else _integers(entry, METER_KEYS)
        if numbers is None:
            raise ScoreInvalid(R.malformed, f"meter {index} must be {{bar, num, den}} integers")
        bar, num, den = numbers
        _check_bar(bar, index, meters[-1].bar if meters else None, R.meters_first_not_zero, R.meters_not_increasing, "meter")
        if not is_valid_meter_value(num, den):
            raise ScoreInvalid(R.meter_invalid, f"meter {index} is {num}/{den}")
        meters.append(Meter(bar=bar, num=num, den=den))
    return tuple(meters)


def _tempos(value: object) -> tuple[Tempo, ...]:
    if not isinstance(value, list):
        raise ScoreInvalid(R.malformed, "tempos must be an array")
    if not value:
        raise ScoreInvalid(R.tempos_empty, "tempos must have an entry at bar 0")
    if len(value) > MAX_TEMPOS:
        raise ScoreInvalid(R.too_many_tempos, f"at most {MAX_TEMPOS} tempos, got {len(value)}")
    tempos: list[Tempo] = []
    for index, raw in enumerate(value):
        entry = _keys_exactly(raw, TEMPO_KEYS)
        bar = None if entry is None else as_integer(entry.get("bar"))
        bpm = None if entry is None else as_number(entry.get("bpm"))
        if bar is None or bpm is None:
            raise ScoreInvalid(R.malformed, f"tempo {index} must be {{bar: integer, bpm: number}}")
        _check_bar(bar, index, tempos[-1].bar if tempos else None, R.tempos_first_not_zero, R.tempos_not_increasing, "tempo")
        if not is_valid_bpm(bpm):
            raise ScoreInvalid(R.tempo_invalid, f"tempo {index} bpm {bpm} is not {BPM_MIN}..{BPM_MAX} with 2 decimals")
        tempos.append(Tempo(bar=bar, bpm=bpm))
    return tuple(tempos)


def parse_snap(value: object) -> Snap:
    if isinstance(value, str):
        for snap in Snap:
            if snap.value == value:
                return snap
    raise ScoreInvalid(R.snap_invalid, f"snap must be one of {[s.value for s in Snap]}")


def _note(raw: object, line: int, index: int) -> Note:
    if not isinstance(raw, Mapping) or any(key not in raw for key in NOTE_KEYS):
        raise ScoreInvalid(R.malformed, "a note must be an object with s, t, d, a", line, index)
    numbers = _integers(raw, NOTE_KEYS)
    if numbers is None:
        raise ScoreInvalid(R.malformed, "s, t, d, a must be integers", line, index)
    s, t, d, a = numbers
    extra = {key: value for key, value in raw.items() if key not in NOTE_KEYS}
    return Note(s=s, t=t, d=d, a=a, extra=extra)


def _lines(value: object, counts: Sequence[int]) -> tuple[ScoreLine, ...]:
    if not isinstance(value, list):
        raise ScoreInvalid(R.malformed, "lines must be an array")
    if len(value) != len(counts):
        raise ScoreInvalid(R.line_count_mismatch, f"the score has {len(value)} lines, the grid {len(counts)}")
    prev_end = 0
    lines: list[ScoreLine] = []
    for line_index, raw_line in enumerate(value):
        entry = _keys_exactly(raw_line, LINE_KEYS)
        if entry is None or not isinstance(entry.get("notes"), list):
            raise ScoreInvalid(R.malformed, "a line must be {notes: [...]}", line_index)
        notes: list[Note] = []
        for note_index, raw_note in enumerate(entry["notes"]):
            note = _note(raw_note, line_index, note_index)
            where = (line_index, note_index)
            if note.s < 0 or note.s >= counts[line_index]:
                raise ScoreInvalid(R.syllable_out_of_range, f"s {note.s} not in 0..{counts[line_index] - 1}", *where)
            if notes and note.s <= notes[-1].s:
                raise ScoreInvalid(R.syllables_not_increasing, f"s {note.s} after s {notes[-1].s}", *where)
            if note.t < 0:
                raise ScoreInvalid(R.start_negative, f"t {note.t} < 0", *where)
            if note.d < 1:
                raise ScoreInvalid(R.duration_invalid, f"d {note.d} < 1", *where)
            if note.a not in ACCENTS:
                raise ScoreInvalid(R.accent_invalid, f"a {note.a} not 0, 1 or 2", *where)
            if note.end > MAX_TICKS:
                raise ScoreInvalid(R.exceeds_max_ticks, f"t + d = {note.end} > {MAX_TICKS}", *where)
            if note.t < prev_end:
                raise ScoreInvalid(R.notes_overlap, f"t {note.t} starts before the previous note ends at {prev_end}", *where)
            prev_end = note.end
            notes.append(note)
        lines.append(ScoreLine(tuple(notes)))
    return tuple(lines)


def check_score(raw: object, grid: GridJson) -> Score:
    """The typed score ``raw`` denotes when it is valid against ``grid``; raises ScoreInvalid."""
    root = _keys_exactly(raw, SCORE_KEYS)
    if root is None:
        raise ScoreInvalid(R.malformed, f"a score is an object with exactly {list(SCORE_KEYS)}")
    version = as_integer(root["v"])
    if version is None:
        raise ScoreInvalid(R.malformed, "v must be an integer")
    if version != VERSION:
        raise ScoreInvalid(R.unsupported_version, f"v {version} is not {VERSION}")
    ppq = as_integer(root["ppq"])
    if ppq is None:
        raise ScoreInvalid(R.malformed, "ppq must be an integer")
    if ppq != PPQ:
        raise ScoreInvalid(R.bad_ppq, f"ppq {ppq} is not {PPQ}")
    meters = _meters(root["meters"])
    tempos = _tempos(root["tempos"])
    pickup = as_integer(root["pickup"])
    if pickup is None:
        raise ScoreInvalid(R.malformed, "pickup must be an integer")
    if pickup < 0 or pickup >= meters[0].bar_length:
        raise ScoreInvalid(R.bad_pickup, f"pickup {pickup} not in 0..{meters[0].bar_length - 1}")
    snap = parse_snap(root["snap"])
    lines = _lines(root["lines"], syllable_counts(grid))
    return Score(pickup=pickup, meters=meters, tempos=tempos, snap=snap, lines=lines)


def check_typed(score: Score, grid: GridJson) -> Score:
    """Re-validate a typed score against a (possibly different) grid."""
    return check_score(score_to_json(score), grid)


@dataclass(frozen=True, slots=True)
class Valid:
    score: Score


@dataclass(frozen=True, slots=True)
class Invalid:
    error: ScoreInvalid


def validate(raw: object, grid: GridJson) -> Valid | Invalid:
    try:
        return Valid(check_score(raw, grid))
    except ScoreInvalid as error:
        return Invalid(error)
