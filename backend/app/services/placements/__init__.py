"""Snippet placements: one snippet, a layout per canvas (docs/features/snippet_placements.md)."""

from app.services.placements.errors import (
    CanvasNotFoundError,
    GroupNotFoundError,
    InvalidCanvasTitleError,
    InvalidListRankError,
    PlacementError,
    PlacementErrorCode,
    PlacementNotFoundError,
    SameCanvasError,
)
from app.services.placements.view import LAYOUT_FIELDS, CanvasRef, PlacedSnippet

__all__ = [
    "CanvasNotFoundError",
    "CanvasRef",
    "GroupNotFoundError",
    "InvalidCanvasTitleError",
    "InvalidListRankError",
    "LAYOUT_FIELDS",
    "PlacedSnippet",
    "PlacementError",
    "PlacementErrorCode",
    "PlacementNotFoundError",
    "SameCanvasError",
]
