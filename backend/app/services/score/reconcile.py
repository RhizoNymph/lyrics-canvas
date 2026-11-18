"""reconcile: keep the notes of syllables that survive a text or grid change (spec section 11)."""

from __future__ import annotations

from app.services.score.grid_view import grid_lines, line_cells, word_groups
from app.services.score.normalize import lcs_pairs, line_key, word_key
from app.services.score.types import GridJson, Note, Score, ScoreLine
from app.services.score.validate import check_typed


def _line_pairs(old_grid: GridJson, new_grid: GridJson) -> dict[int, int]:
    """new line index -> old line index."""
    old_lines, new_lines = grid_lines(old_grid), grid_lines(new_grid)
    if len(old_lines) == len(new_lines):
        return {index: index for index in range(len(new_lines))}
    pairs = lcs_pairs([line_key(line) for line in old_lines], [line_key(line) for line in new_lines])
    return {new: old for old, new in pairs}


def _mapped_notes(old_line_cells: list, new_line_cells: list, old_notes: ScoreLine) -> list[Note]:
    old_groups, new_groups = word_groups(old_line_cells), word_groups(new_line_cells)
    pairs = lcs_pairs([word_key(g.spelling) for g in old_groups], [word_key(g.spelling) for g in new_groups])
    by_s = old_notes.by_syllable()
    candidates: list[Note] = []
    for old_index, new_index in pairs:
        old_ordinals, new_ordinals = old_groups[old_index].ordinals, new_groups[new_index].ordinals
        for r in range(min(len(old_ordinals), len(new_ordinals))):
            note = by_s.get(old_ordinals[r])
            if note is not None:
                candidates.append(note.with_syllable(new_ordinals[r]))
    return sorted(candidates, key=lambda note: note.s)


def reconcile(score: Score, old_grid: GridJson, new_grid: GridJson) -> Score:
    """The score for ``new_grid``: matched words keep their syllables' notes, the rest is unplaced.
    Raises ScoreInvalid when ``score`` is not valid against ``old_grid``."""
    check_typed(score, old_grid)
    old_lines, new_lines = grid_lines(old_grid), grid_lines(new_grid)
    pairs = _line_pairs(old_grid, new_grid)
    prev_end = 0
    lines: list[ScoreLine] = []
    for new_index, new_line in enumerate(new_lines):
        old_index = pairs.get(new_index)
        if old_index is None:
            lines.append(ScoreLine())
            continue
        kept: list[Note] = []
        for note in _mapped_notes(line_cells(old_lines[old_index]), line_cells(new_line), score.lines[old_index]):
            if note.t >= prev_end:
                kept.append(note)
                prev_end = note.end
        lines.append(ScoreLine(tuple(kept)))
    return check_typed(score.with_lines(tuple(lines)), new_grid)
