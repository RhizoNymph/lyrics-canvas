"""Canvas title validation (docs/features/snippet_placements.md#canvas-titles).

A title is stripped of surrounding whitespace and must then be 1-255 code points (the column
width). On create an empty title falls back to ``DEFAULT_CANVAS_TITLE``; on rename it is an error.
"""

from __future__ import annotations

from typing import NewType

from app.services.placements.errors import InvalidCanvasTitleError

MAX_CANVAS_TITLE_LENGTH = 255
DEFAULT_CANVAS_TITLE = "Untitled Canvas"
FORK_SUFFIX = " (fork)"

CanvasTitle = NewType("CanvasTitle", str)


def _bounded(title: str) -> CanvasTitle:
    if len(title) > MAX_CANVAS_TITLE_LENGTH:
        raise InvalidCanvasTitleError(f"a canvas title is at most {MAX_CANVAS_TITLE_LENGTH} characters")
    return CanvasTitle(title)


def rename_title(raw: str) -> CanvasTitle:
    title = raw.strip()
    if not title:
        raise InvalidCanvasTitleError("a canvas title cannot be empty")
    return _bounded(title)


def new_canvas_title(raw: str | None) -> CanvasTitle:
    title = (raw or "").strip()
    return _bounded(title) if title else CanvasTitle(DEFAULT_CANVAS_TITLE)


def fork_title(original: str) -> CanvasTitle:
    """``"<original> (fork)"``, the original cut short so the result fits the column."""
    return CanvasTitle(original[: MAX_CANVAS_TITLE_LENGTH - len(FORK_SUFFIX)] + FORK_SUFFIX)
