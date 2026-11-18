"""The rest link in both directions (spec/score_format.md sections 8 and 9).

Score -> grid: gaps between consecutive placed syllables become rest cells. Grid -> score: a grid
edit that only changed rest cells moves the notes, then the score -> grid direction makes the grid
canonical.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from app.services.rest_marks import rebuild_line_text
from app.services.score.errors import NotRestOnlyEdit
from app.services.score.grid_view import (
    Cell,
    grid_lines,
    line_cells,
    rests_between,
    syllable_identity,
    syllable_positions,
)
from app.services.score.timing import beat_unit
from app.services.score.types import GridJson, Note, Score, ScoreLine
from app.services.score.validate import check_typed


def _rest_cell() -> dict[str, Any]:
    return {"rest": True}


def _linked_cells(score: Score, cells: Sequence[Cell], line: ScoreLine) -> list[Cell]:
    syl = syllable_positions(cells)
    if not syl:
        return list(cells)
    notes = line.by_syllable()
    out: list[Cell] = list(cells[: syl[0] + 1])
    for k in range(len(syl) - 1):
        run = list(cells[syl[k] + 1 : syl[k + 1]])
        left, right = notes.get(k), notes.get(k + 1)
        if left is not None and right is not None:
            gap = right.t - left.end
            target = gap // beat_unit(score, left.end)
            out.extend(run[:target])
            out.extend(_rest_cell() for _ in range(target - len(run)))
        else:
            out.extend(run)
        out.append(cells[syl[k + 1]])
    out.extend(cells[syl[-1] + 1 :])
    return out


def _with_line(grid: GridJson, lines: list[Mapping[str, Any]]) -> dict[str, Any]:
    base: dict[str, Any] = dict(grid) if grid is not None else {}
    base["lines"] = lines
    return base


def apply_rest_link(score: Score, grid: GridJson) -> GridJson:
    """The grid with the rest count between every two consecutive placed syllables set from the
    score. Lines whose cells do not change are returned as they are (``raw_text`` untouched)."""
    check_typed(score, grid)
    if grid is None:
        return None
    out_lines: list[Mapping[str, Any]] = []
    for line, score_line in zip(grid_lines(grid), score.lines, strict=True):
        cells = line_cells(line)
        linked = _linked_cells(score, cells, score_line)
        if linked == cells:
            out_lines.append(line)
            continue
        new_line = dict(line)
        new_line["cells"] = linked
        new_line["raw_text"] = rebuild_line_text(linked)
        out_lines.append(new_line)
    return _with_line(grid, out_lines)


def is_rest_only_edit(old_grid: GridJson, new_grid: GridJson) -> bool:
    """Same lines with the same syllables (``syllable`` and ``word_index``); only rests differ."""
    old_lines, new_lines = grid_lines(old_grid), grid_lines(new_grid)
    if len(old_lines) != len(new_lines):
        return False
    return all(
        syllable_identity(line_cells(old)) == syllable_identity(line_cells(new))
        for old, new in zip(old_lines, new_lines, strict=True)
    )


@dataclass(frozen=True, slots=True)
class GridEditResult:
    score: Score
    grid: GridJson


def _shift(notes: list[Note], delta: int, from_s: int = 0) -> list[Note]:
    return [note.moved(delta) if note.s >= from_s else note for note in notes]


def apply_grid_rest_edit(score: Score, old_grid: GridJson, new_grid: GridJson) -> GridEditResult:
    """Move the notes for a rest-only grid edit, then make the grid canonical (spec section 9).

    Raises ScoreInvalid when ``score`` is not valid against ``old_grid`` or the moved score would
    pass MAX_TICKS, and NotRestOnlyEdit when the edit changed syllables."""
    check_typed(score, old_grid)
    if not is_rest_only_edit(old_grid, new_grid):
        raise NotRestOnlyEdit("the grid edit changed syllables or lines, not only rests")
    old_lines, new_lines = grid_lines(old_grid), grid_lines(new_grid)
    prev_end = 0
    moved_lines: list[ScoreLine] = []
    for index, line in enumerate(score.lines):
        notes = list(line.notes)
        if not notes:
            moved_lines.append(line)
            continue
        notes = _shift(notes, max(0, prev_end - notes[0].t))
        old_r = rests_between(line_cells(old_lines[index]))
        new_r = rests_between(line_cells(new_lines[index]))
        for k in range(len(old_r)):
            by_s = {note.s: note for note in notes}
            left, right = by_s.get(k), by_s.get(k + 1)
            if left is None or right is None:
                continue
            delta = new_r[k] - old_r[k]
            if delta == 0:
                continue
            amount = delta * beat_unit(score, left.end)
            if amount < 0:
                amount = -min(-amount, right.t - left.end)
            notes = _shift(notes, amount, k + 1)
        prev_end = notes[-1].end
        moved_lines.append(ScoreLine(tuple(notes)))
    moved = check_typed(score.with_lines(tuple(moved_lines)), new_grid)
    return GridEditResult(score=moved, grid=apply_rest_link(moved, new_grid))
