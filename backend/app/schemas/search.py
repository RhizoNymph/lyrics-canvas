"""Response and error models of ``GET /api/search`` (docs/features/global_search.md)."""

from __future__ import annotations

from pydantic import BaseModel

from app.models.snippet import SnippetType
from app.schemas.snippet import SnippetCanvasRef


class SearchCanvasHit(BaseModel):
    id: str
    title: str
    updated_at: str


class SearchSnippetHit(BaseModel):
    snippet_id: str
    snippet_type: SnippetType
    content_updated_at: str
    excerpt: str  # display text, no rest marks; at most 160 code points
    canvases: list[SnippetCanvasRef]  # every canvas holding the snippet: lower(title), then id


class SearchResponse(BaseModel):
    canvases: list[SearchCanvasHit]  # titles matching every term, updated_at desc
    snippets: list[SearchSnippetHit]  # display text matching every term, content_updated_at desc, then id


class SearchErrorBody(BaseModel):
    error: str  # a SearchErrorCode value
    message: str


class SearchErrorResponse(BaseModel):
    detail: SearchErrorBody
