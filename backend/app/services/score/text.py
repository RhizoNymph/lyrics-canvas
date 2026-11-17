"""Keep a snippet's raw_text in step with rest-linked grid lines (spec/score_format.md section 13)."""

from __future__ import annotations

from collections.abc import Mapping

from app.services.score.errors import ScoreTextMismatch
from app.services.score.grid_view import grid_lines
from app.services.score.types import GridJson


def changed_line_texts(old_grid: GridJson, new_grid: GridJson) -> dict[int, str]:
    """Grid line index -> new ``raw_text`` for every line whose ``raw_text`` changed."""
    old_lines, new_lines = grid_lines(old_grid), grid_lines(new_grid)
    if len(old_lines) != len(new_lines):
        raise ValueError("changed_line_texts needs grids with the same number of lines")
    return {
        index: str(new.get("raw_text") or "")
        for index, (old, new) in enumerate(zip(old_lines, new_lines, strict=True))
        if old.get("raw_text") != new.get("raw_text")
    }


def splice_line_texts(raw_text: str, replacements: Mapping[int, str]) -> str:
    """Replace the text lines that are grid lines ``replacements`` (the n-th non-blank line is grid
    line n); blank lines and other lines are kept byte for byte. Raises ScoreTextMismatch."""
    pieces = raw_text.split("\n")
    grid_index = 0
    for position, piece in enumerate(pieces):
        if not piece.strip():
            continue
        if grid_index in replacements:
            pieces[position] = replacements[grid_index]
        grid_index += 1
    missing = [index for index in replacements if index >= grid_index or index < 0]
    if missing:
        raise ScoreTextMismatch(min(missing), grid_index)
    return "\n".join(pieces)
