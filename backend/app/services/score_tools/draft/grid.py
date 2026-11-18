"""Draft a score from the grid (spec/score_format.md section 12, docs/features/score_draft_export.md)."""

from __future__ import annotations

from collections.abc import Mapping

from app.services.score import draft_from_grid
from app.services.score.types import GridJson
from app.services.score_tools.draft.types import DraftResult
from app.services.score_tools.errors import DraftEmptyError


def draft_grid(grid: GridJson, meter: Mapping[str, object], bpm: object, note: object) -> DraftResult:
    """``draft_from_grid`` with every syllable placed. Raises ScoreInvalid (meter_invalid,
    tempo_invalid, snap_invalid, exceeds_max_ticks) or DraftEmptyError (no syllable in the grid)."""
    score = draft_from_grid(grid, meter, bpm, note)
    if not score.all_notes():
        raise DraftEmptyError("the snippet's grid has no syllables")
    return DraftResult(score=score, bpm_estimated=None, unplaced=0, unmatched_words=0)
