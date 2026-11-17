"""The authoritative score save flow (docs/features/score_backend.md#save-flow).

Every path that writes a snippet's text, grid or score runs one of the two pure functions here, so
the stored score is always valid for the stored grid and the grid's rests always follow the score
(spec/score_format.md sections 8, 9, 11 and 13):

* ``saved_content(stored, ...)``: an edit of an existing snippet (PUT /api/snippets/{id}, the voice
  take append). The new score is the sent one (validated against the resulting grid), none (an
  explicit ``score: null``), or the stored one carried over the grid change: an unchanged grid keeps
  it, a rest-only grid edit moves its notes (``apply_grid_rest_edit``), any other change reconciles
  it (``reconcile``). Then the rest link writes the score's gaps into the grid and the changed grid
  lines into ``raw_text``.
* ``new_content(...)``: a new snippet (POST /api/canvases/{id}/snippets): the sent score, if any,
  validated against the new grid, then the rest link.

``score_view`` is the sent value, else the stored one, and always False without a score.

Callers compute phonetics, card size and everything else that reads the text or grid from the
returned ``ScoredContent`` (the final text and grid), never from what the client sent.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, TypeAlias

from app.services.score import (
    Score,
    ScoreError,
    ScoreInvalid,
    apply_grid_rest_edit,
    apply_rest_link,
    changed_line_texts,
    check_score,
    is_rest_only_edit,
    reconcile,
    score_to_json,
    splice_line_texts,
)

logger = logging.getLogger(__name__)

GridDict: TypeAlias = dict[str, Any] | None
ScoreJson: TypeAlias = dict[str, Any]


# ---- what the client asked for ----


@dataclass(frozen=True, slots=True)
class KeepScore:
    """``score`` not sent: carry the stored score over the text / grid change."""


@dataclass(frozen=True, slots=True)
class RemoveScore:
    """``score: null`` sent: the snippet has no score afterwards ("Remove score")."""


@dataclass(frozen=True, slots=True)
class SetScore:
    """``score: {...}`` sent: raw JSON, validated against the resulting grid by ``check_score``."""

    raw: object


ScoreEdit: TypeAlias = KeepScore | RemoveScore | SetScore


def score_edit(fields_set: frozenset[str] | set[str], raw: object) -> ScoreEdit:
    """The edit a request body asks for: ``score`` absent, explicitly null, or a value."""
    if "score" not in fields_set:
        return KeepScore()
    if raw is None:
        return RemoveScore()
    return SetScore(raw)


# ---- content in and out ----


@dataclass(frozen=True, slots=True)
class ScoredContent:
    """A snippet's text, grid and score as stored: ``score`` is canonical JSON valid for ``grid``
    (or None) and the grid's placed-placed rests follow it; ``score_view`` is False when ``score``
    is None."""

    raw_text: str
    grid: GridDict
    score: ScoreJson | None
    score_view: bool


class StoredScoreInvalid(ScoreError):
    """The stored score is not valid for the stored grid: a write bypassed the save flow. Not the
    client's fault, so it is not an ``invalid_score`` 422 (the route answers 500)."""

    def __init__(self, cause: ScoreInvalid) -> None:
        super().__init__(f"stored score does not fit the stored grid: {cause}")
        self.cause = cause


def _linked(raw_text: str, grid: GridDict, score: Score | None) -> tuple[str, GridDict]:
    """The rest link (spec section 8) and the text splice (section 13). Raises ScoreTextMismatch
    when a re-linked grid line has no text line (a client grid that disagrees with its text)."""
    if score is None:
        return raw_text, grid
    linked = apply_rest_link(score, grid)
    if linked is None or grid is None:
        return raw_text, grid
    replacements = changed_line_texts(grid, linked)
    if not replacements:
        return raw_text, grid
    return splice_line_texts(raw_text, replacements), dict(linked)


def _content(raw_text: str, grid: GridDict, score: Score | None, view: bool) -> ScoredContent:
    text, linked_grid = _linked(raw_text, grid, score)
    return ScoredContent(
        raw_text=text,
        grid=linked_grid,
        score=None if score is None else score_to_json(score),
        score_view=view and score is not None,
    )


def _stored_score(stored: ScoredContent) -> Score | None:
    if stored.score is None:
        return None
    try:
        return check_score(stored.score, stored.grid)
    except ScoreInvalid as exc:
        logger.error("score.stored_invalid reason=%s line=%s note=%s", exc.reason.value, exc.line, exc.note)
        raise StoredScoreInvalid(exc) from exc


def _carried(old: Score, old_grid: GridDict, new_grid: GridDict) -> tuple[Score, GridDict]:
    """The stored score over a grid change it did not come with (spec sections 9 and 11)."""
    if old_grid == new_grid:
        return old, new_grid
    if is_rest_only_edit(old_grid, new_grid):
        # Raises ScoreInvalid(exceeds_max_ticks) when a note would be pushed past MAX_TICKS:
        # the edit is refused.
        result = apply_grid_rest_edit(old, old_grid, new_grid)
        return result.score, None if result.grid is None else dict(result.grid)
    return reconcile(old, old_grid, new_grid), new_grid


def saved_content(
    stored: ScoredContent, *, raw_text: str, grid: GridDict, edit: ScoreEdit, view: bool | None
) -> ScoredContent:
    """The content an edit stores (module docstring). ``raw_text`` and ``grid`` are the edit's text
    and grid (sent, re-syllabified or unchanged). Raises ScoreInvalid (the client's score, or a
    rest-only edit pushing notes past MAX_TICKS), ScoreTextMismatch and StoredScoreInvalid."""
    final_view = stored.score_view if view is None else view
    match edit:
        case SetScore(raw=raw):
            score: Score | None = check_score(raw, grid)
        case RemoveScore():
            score = None
        case KeepScore():
            old = _stored_score(stored)
            if old is None:
                score = None
            else:
                score, grid = _carried(old, stored.grid, grid)
    content = _content(raw_text, grid, score, final_view)
    logger.debug(
        "score.save edit=%s had_score=%s has_score=%s view=%s text_changed=%s",
        type(edit).__name__, stored.score is not None, content.score is not None, content.score_view,
        content.raw_text != raw_text,
    )
    return content


def new_content(*, raw_text: str, grid: GridDict, raw_score: object, view: bool) -> ScoredContent:
    """A new snippet's content: ``raw_score`` (None = no score) validated against ``grid``, then the
    rest link. Raises ScoreInvalid and ScoreTextMismatch."""
    score = None if raw_score is None else check_score(raw_score, grid)
    return _content(raw_text, grid, score, view)
