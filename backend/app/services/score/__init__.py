"""Snippet score ("musical mode"): format v1 and its shared algorithms.

The normative spec is spec/score_format.md; spec/score_vectors.json (generated from this package by
scripts/gen_score_vectors.py) holds the vectors the web and Android ports must pass.
"""

from app.services.score.codec import note_to_json, score_to_json
from app.services.score.constants import MAX_BAR, MAX_TICKS, PPQ, VERSION, WHOLE
from app.services.score.draft import default_accent, draft_from_grid
from app.services.score.errors import (
    NotRestOnlyEdit,
    ScoreArgumentError,
    ScoreError,
    ScoreInvalid,
    ScoreReason,
    ScoreTextMismatch,
)
from app.services.score.normalize import lcs_pairs, line_key, normalize_word, word_key
from app.services.score.reconcile import reconcile
from app.services.score.rest_link import GridEditResult, apply_grid_rest_edit, apply_rest_link, is_rest_only_edit
from app.services.score.text import changed_line_texts, splice_line_texts
from app.services.score.timing import (
    PICKUP_BAR,
    BarPosition,
    bar_at_tick,
    bar_count,
    bar_length,
    bar_start_tick,
    beat_unit,
    end_tick,
    metric_weight,
    tick_to_ms,
)
from app.services.score.types import SNAP_TICKS, GridJson, Meter, Note, Score, ScoreLine, Snap, Tempo
from app.services.score.validate import Invalid, Valid, check_score, check_typed, validate
from app.services.score.wire import ScoreModel

__all__ = [
    "BarPosition",
    "GridEditResult",
    "GridJson",
    "Invalid",
    "MAX_BAR",
    "MAX_TICKS",
    "Meter",
    "Note",
    "NotRestOnlyEdit",
    "PICKUP_BAR",
    "PPQ",
    "SNAP_TICKS",
    "Score",
    "ScoreArgumentError",
    "ScoreError",
    "ScoreInvalid",
    "ScoreLine",
    "ScoreModel",
    "ScoreReason",
    "ScoreTextMismatch",
    "Snap",
    "Tempo",
    "VERSION",
    "Valid",
    "WHOLE",
    "apply_grid_rest_edit",
    "apply_rest_link",
    "bar_at_tick",
    "bar_count",
    "bar_length",
    "bar_start_tick",
    "beat_unit",
    "changed_line_texts",
    "check_score",
    "check_typed",
    "default_accent",
    "draft_from_grid",
    "end_tick",
    "is_rest_only_edit",
    "lcs_pairs",
    "line_key",
    "metric_weight",
    "normalize_word",
    "note_to_json",
    "reconcile",
    "score_to_json",
    "splice_line_texts",
    "tick_to_ms",
    "validate",
    "word_key",
]
