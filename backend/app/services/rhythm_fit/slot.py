"""The anchor slot's rhythm, read from the anchor snippet's score (docs/features/rhyme_rhythm_fit.md).

Slot syllables are the syllable cells of the anchor's grid line whose ``word_index`` is in the
anchor's word range ``[word_offset, word_end)`` (``word_end`` None = to the end of the line), in
order. Notes refer to syllables by their ordinal among the line's syllable cells, so rests are
counted for nothing and the slot's own rests show only in the onset ticks.
"""

from __future__ import annotations

import logging
from collections.abc import Mapping, Sequence
from typing import Any

from app.services.score import GridJson, Score, ScoreInvalid, check_score, metric_weight
from app.services.rhythm_fit.types import RhythmUnavailable, SlotBeat, SlotRhythm, SlotRhythmResult

logger = logging.getLogger(__name__)


def _grid_lines(grid: GridJson) -> Sequence[Mapping[str, Any]]:
    if grid is None:
        return ()
    lines = grid.get("lines")
    return lines if isinstance(lines, list) else ()


def slot_ordinals(line: Mapping[str, Any], word_offset: int, word_end: int | None) -> list[int]:
    """Ordinals (among the line's syllable cells) of the syllables of words ``[word_offset, word_end)``."""
    ordinals: list[int] = []
    ordinal = 0
    cells = line.get("cells")
    for cell in cells if isinstance(cells, list) else ():
        if isinstance(cell, Mapping) and cell.get("rest") is True:
            continue
        word_index = cell.get("word_index") if isinstance(cell, Mapping) else None
        if isinstance(word_index, int) and word_index >= word_offset and (word_end is None or word_index < word_end):
            ordinals.append(ordinal)
        ordinal += 1
    return ordinals


def slot_rhythm(score: Score, grid: GridJson, line_index: int, word_offset: int, word_end: int | None) -> SlotRhythmResult:
    """The slot rhythm of words ``[word_offset, word_end)`` of grid line ``line_index``.

    ``slot_unplaced`` when the range has no syllable cell or any of its syllables has no note.
    ``score`` must be valid for ``grid`` (``check_score``).
    """
    lines = _grid_lines(grid)
    if not 0 <= line_index < len(lines) or line_index >= len(score.lines):
        return RhythmUnavailable("slot_unplaced")
    ordinals = slot_ordinals(lines[line_index], word_offset, word_end)
    if not ordinals:
        return RhythmUnavailable("slot_unplaced")
    notes = score.lines[line_index].by_syllable()
    beats: list[SlotBeat] = []
    for s in ordinals:
        note = notes.get(s)
        if note is None:
            return RhythmUnavailable("slot_unplaced")
        beats.append(SlotBeat(onset=note.t, weight=metric_weight(score, note.t), accent=note.a))
    return SlotRhythm(tuple(beats))


def stored_slot_rhythm(
    raw_score: object | None, grid: GridJson, line_index: int, word_offset: int, word_end: int | None, snippet_id: str = ""
) -> SlotRhythmResult:
    """``slot_rhythm`` from a snippet's stored score JSON: ``no_score`` when it has none.

    The server validates every score it stores, so a stored score that no longer validates against
    the stored grid is a data error: it is logged (warn) and treated as no score.
    """
    if raw_score is None:
        return RhythmUnavailable("no_score")
    try:
        score = check_score(raw_score, grid)
    except ScoreInvalid as exc:
        logger.warning("rhythm_fit.stored_score_invalid snippet_id=%s reason=%s", snippet_id, exc.reason)
        return RhythmUnavailable("no_score")
    return slot_rhythm(score, grid, line_index, word_offset, word_end)
