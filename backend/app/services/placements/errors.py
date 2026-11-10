"""Typed failures of the canvas and placement operations (docs/features/snippet_placements.md#errors).

Each carries its wire code and HTTP status; app/api/placement_errors.py turns them into
``HTTPException(status, {"error": code, "message": message})``.
"""

from __future__ import annotations

from enum import StrEnum
from typing import ClassVar


class PlacementErrorCode(StrEnum):
    invalid_canvas_title = "invalid_canvas_title"
    placement_not_found = "placement_not_found"
    canvas_not_found = "canvas_not_found"
    same_canvas = "same_canvas"
    group_not_found = "group_not_found"


class PlacementError(Exception):
    code: ClassVar[PlacementErrorCode]
    status: ClassVar[int]

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class InvalidCanvasTitleError(PlacementError):
    """After stripping, the title is empty (rename) or longer than the limit."""

    code = PlacementErrorCode.invalid_canvas_title
    status = 422


class PlacementNotFoundError(PlacementError):
    """The snippet is not placed on that canvas, or the canvas or snippet is not the caller's."""

    code = PlacementErrorCode.placement_not_found
    status = 404


class CanvasNotFoundError(PlacementError):
    code = PlacementErrorCode.canvas_not_found
    status = 404


class SameCanvasError(PlacementError):
    """A transfer whose target is its source canvas."""

    code = PlacementErrorCode.same_canvas
    status = 422


class GroupNotFoundError(PlacementError):
    """``group_id`` does not name a group of the placement's canvas."""

    code = PlacementErrorCode.group_not_found
    status = 404
