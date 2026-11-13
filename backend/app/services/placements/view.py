"""A snippet as seen on one canvas: its shared content plus that canvas's placement.

``PlacedSnippet`` exposes the flat attribute names ``SnippetResponse`` has always had, so the
response builder (app/api/canvases.py ``_snippet_response``) reads content from the snippet and
layout (``canvas_id, position_x, position_y, width, height, z_index, group_id``) from the
placement. ``canvases`` lists every canvas holding the snippet, sorted by ``title.lower()`` then id.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime

from app.models.placement import SnippetPlacement
from app.models.snippet import GridMode, Snippet, SnippetType

# The per-canvas columns; everything else on a snippet is shared content.
LAYOUT_FIELDS: frozenset[str] = frozenset({"position_x", "position_y", "width", "height", "z_index", "group_id"})


@dataclass(frozen=True, slots=True)
class CanvasRef:
    id: uuid.UUID
    title: str


def sort_canvas_refs(refs: list[CanvasRef]) -> tuple[CanvasRef, ...]:
    return tuple(sorted(refs, key=lambda ref: (ref.title.lower(), str(ref.id))))


@dataclass(frozen=True, slots=True)
class PlacedSnippet:
    snippet: Snippet
    placement: SnippetPlacement
    canvases: tuple[CanvasRef, ...]

    # ---- shared content ----

    @property
    def id(self) -> uuid.UUID:
        return self.snippet.id

    @property
    def owner_id(self) -> uuid.UUID:
        return self.snippet.owner_id

    @property
    def snippet_type(self) -> SnippetType:
        return self.snippet.snippet_type

    @property
    def grid_mode(self) -> GridMode:
        return self.snippet.grid_mode

    @property
    def beats_per_line(self) -> int | None:
        return self.snippet.beats_per_line

    @property
    def grid_data(self) -> dict | None:
        return self.snippet.grid_data

    @property
    def raw_text(self) -> str:
        return self.snippet.raw_text

    @property
    def tags(self) -> list:
        return self.snippet.tags

    @property
    def phonetics(self) -> dict | None:
        return self.snippet.phonetics

    @property
    def derived_from(self) -> dict | None:
        return self.snippet.derived_from

    @property
    def created_at(self) -> datetime:
        return self.snippet.created_at

    @property
    def updated_at(self) -> datetime:
        return self.snippet.updated_at

    @property
    def content_updated_at(self) -> datetime:
        return self.snippet.content_updated_at

    @property
    def version(self) -> int:
        return self.snippet.version

    # ---- this canvas's layout ----

    @property
    def placement_id(self) -> uuid.UUID:
        return self.placement.id

    @property
    def canvas_id(self) -> uuid.UUID:
        return self.placement.canvas_id

    @property
    def position_x(self) -> float:
        return self.placement.position_x

    @property
    def position_y(self) -> float:
        return self.placement.position_y

    @property
    def width(self) -> float:
        return self.placement.width

    @property
    def height(self) -> float:
        return self.placement.height

    @property
    def z_index(self) -> int:
        return self.placement.z_index

    @property
    def group_id(self) -> uuid.UUID | None:
        return self.placement.group_id
