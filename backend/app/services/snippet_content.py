"""Derived snippet content shared by every path that creates or rewrites a snippet's text:
the beat grid, the phonetics computed from it, and the card size.

``POST /api/canvases/{id}/snippets`` and the voice-take endpoint both build new snippets with
``derive_new_content``; appends and merges size their grids with ``compute_size``.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

from app.schemas.phonetics import PronunciationOverride
from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics_service import compute_snippet_phonetics

# Must match frontend SnippetCard constants
CELL_WIDTH = 60
CELL_HEIGHT = 22
PADDING = 12
HEADER_HEIGHT = 28
MIN_WIDTH = 200
MIN_HEIGHT = 80


def compute_size(grid_data: Mapping[str, object]) -> tuple[float, float]:
    """Compute snippet width/height from grid data.

    Width = (max_cells + 1) * CELL_WIDTH + padding  ("+1" leaves room for one more syllable)
    Height = num_lines * CELL_HEIGHT + header + padding
    Floats, as the columns store them, so a fresh snippet serializes like a reloaded one.
    """
    lines = grid_data.get("lines", [])
    if not isinstance(lines, list) or not lines:
        return (float(MIN_WIDTH), float(MIN_HEIGHT))
    max_cells = max((len(line.get("cells", [])) for line in lines), default=0)
    width = max((max_cells + 1) * CELL_WIDTH + PADDING * 2 + 20, MIN_WIDTH)  # +20 for syllable count column
    height = max(len(lines) * CELL_HEIGHT + HEADER_HEIGHT + PADDING * 2, MIN_HEIGHT)
    return (float(width), float(height))


@dataclass(frozen=True, slots=True)
class SnippetContent:
    raw_text: str
    grid_data: dict
    phonetics: dict  # SnippetPhonetics, JSON mode
    width: float
    height: float


def content_for(
    raw_text: str, grid_data: dict, overrides: Mapping[str, PronunciationOverride] | None = None
) -> SnippetContent:
    """Phonetics and size for text with a given grid (grid stress wins via overrides)."""
    phonetics = compute_snippet_phonetics(raw_text, grid_data, overrides).model_dump(mode="json")
    width, height = compute_size(grid_data)
    return SnippetContent(raw_text=raw_text, grid_data=grid_data, phonetics=phonetics, width=width, height=height)


def derive_new_content(raw_text: str, grid_data: dict | None = None) -> SnippetContent:
    """A new snippet's content: a client-supplied grid is stored as-is (so merged rests and stress
    survive), otherwise the text is syllabified."""
    if grid_data is None:
        grid_data = syllabify_text(raw_text) if raw_text else {"lines": []}
    return content_for(raw_text, grid_data)
