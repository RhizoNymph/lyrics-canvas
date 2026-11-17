"""draft_from_grid: a first score laid out cell by cell (spec/score_format.md section 12)."""

from __future__ import annotations

from collections.abc import Mapping

from app.services.score.constants import WHOLE
from app.services.score.errors import ScoreInvalid, ScoreReason
from app.services.score.grid_view import grid_lines, is_rest, line_cells
from app.services.score.types import GridJson, Meter, Note, Score, ScoreLine, Tempo
from app.services.score.validate import as_integer, as_number, check_typed, is_valid_bpm, is_valid_meter_value, parse_snap


def default_accent(stress: object) -> int:
    """Grid stress 1 -> accent 1, anything else (0, 2, missing) -> 0."""
    return 1 if as_integer(stress) == 1 else 0


def draft_from_grid(grid: GridJson, meter: Mapping[str, object], bpm: object, note: object) -> Score:
    """Each syllable takes one ``note`` length, each rest one beat unit, each line starts on a bar.
    Raises ScoreInvalid (meter_invalid, tempo_invalid, snap_invalid, exceeds_max_ticks)."""
    meter_num, meter_den = as_integer(meter.get("num")), as_integer(meter.get("den"))
    if meter_num is None or meter_den is None or not is_valid_meter_value(meter_num, meter_den):
        raise ScoreInvalid(ScoreReason.meter_invalid, f"meter {meter.get('num')}/{meter.get('den')} is not valid")
    tempo = as_number(bpm)
    if tempo is None or not is_valid_bpm(tempo):
        raise ScoreInvalid(ScoreReason.tempo_invalid, f"bpm {bpm} is not valid")
    snap = parse_snap(note)
    first = Meter(bar=0, num=meter_num, den=meter_den)
    length, beat, bar = snap.ticks, WHOLE // meter_den, first.bar_length
    cursor = 0
    lines: list[ScoreLine] = []
    for index, line in enumerate(grid_lines(grid)):
        if index > 0:
            cursor = -(-cursor // bar) * bar
        notes: list[Note] = []
        ordinal = 0
        for cell in line_cells(line):
            if is_rest(cell):
                cursor += beat
                continue
            notes.append(Note(s=ordinal, t=cursor, d=length, a=default_accent(cell.get("stress"))))
            ordinal += 1
            cursor += length
        lines.append(ScoreLine(tuple(notes)))
    score = Score(pickup=0, meters=(first,), tempos=(Tempo(bar=0, bpm=tempo),), snap=snap, lines=tuple(lines))
    return check_typed(score, grid)
