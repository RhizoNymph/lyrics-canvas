"""Search across the caller's canvases (``GET /api/search``) and within one canvas.

Both read candidates with the escaped ILIKE prefilter (``sql.terms_filter``) and confirm each one in
Python on display text (``matching``), so limits count confirmed matches only. Ownership: canvases
by ``canvases.owner_id``, snippets by ``snippets.owner_id``.
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator, Callable, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from sqlalchemy import Select, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement
from app.models.snippet import Snippet, SnippetType
from app.services.placements.queries import canvas_refs
from app.services.placements.view import CanvasRef
from app.services.search.excerpt import excerpt
from app.services.search.matching import matches_terms, title_matches
from app.services.search.query import SearchQuery
from app.services.search.sql import terms_filter

CHUNK = 200  # candidate rows read per round trip while filling a limit


@dataclass(frozen=True, slots=True)
class CanvasHit:
    id: uuid.UUID
    title: str
    updated_at: datetime


@dataclass(frozen=True, slots=True)
class SnippetHit:
    snippet_id: uuid.UUID
    snippet_type: SnippetType
    content_updated_at: datetime
    excerpt: str
    canvases: tuple[CanvasRef, ...]


@dataclass(frozen=True, slots=True)
class SearchResults:
    canvases: tuple[CanvasHit, ...]
    snippets: tuple[SnippetHit, ...]


async def _chunks(db: AsyncSession, query: Select) -> AsyncIterator[Sequence[Any]]:
    offset = 0
    while True:
        rows = (await db.execute(query.limit(CHUNK).offset(offset))).all()
        if not rows:
            return
        yield rows
        if len(rows) < CHUNK:
            return
        offset += CHUNK


async def _confirmed(db: AsyncSession, query: Select, confirm: Callable[[Any], bool], limit: int) -> list[Any]:
    """The first ``limit`` rows of ``query`` (in its order) that ``confirm`` accepts."""
    found: list[Any] = []
    async for rows in _chunks(db, query):
        for row in rows:
            if confirm(row):
                found.append(row)
                if len(found) >= limit:
                    return found
    return found


async def matching_canvases(db: AsyncSession, owner_id: uuid.UUID, query: SearchQuery, limit: int) -> list[CanvasHit]:
    candidates = (
        select(Canvas.id, Canvas.title, Canvas.updated_at)
        .where(Canvas.owner_id == owner_id, terms_filter(Canvas.title, query.terms))
        .order_by(Canvas.updated_at.desc(), Canvas.id)
    )
    rows = await _confirmed(db, candidates, lambda row: title_matches(query.terms, row.title), limit)
    return [CanvasHit(id=row.id, title=row.title, updated_at=row.updated_at) for row in rows]


async def matching_snippets(db: AsyncSession, owner_id: uuid.UUID, query: SearchQuery, limit: int) -> list[SnippetHit]:
    candidates = (
        select(Snippet.id, Snippet.snippet_type, Snippet.content_updated_at, Snippet.raw_text)
        .where(Snippet.owner_id == owner_id, terms_filter(Snippet.raw_text, query.terms))
        .order_by(Snippet.content_updated_at.desc(), Snippet.id)
    )
    rows = await _confirmed(db, candidates, lambda row: matches_terms(query.terms, row.raw_text), limit)
    refs = await canvas_refs(db, (row.id for row in rows))
    return [
        SnippetHit(
            snippet_id=row.id,
            snippet_type=row.snippet_type,
            content_updated_at=row.content_updated_at,
            excerpt=excerpt(row.raw_text, query.terms),
            canvases=refs.get(row.id, ()),
        )
        for row in rows
    ]


async def search_everything(db: AsyncSession, owner_id: uuid.UUID, query: SearchQuery, limit: int) -> SearchResults:
    canvases = await matching_canvases(db, owner_id, query, limit)
    snippets = await matching_snippets(db, owner_id, query, limit)
    return SearchResults(canvases=tuple(canvases), snippets=tuple(snippets))


async def search_canvas(
    db: AsyncSession, canvas_id: uuid.UUID, owner_id: uuid.UUID, terms: Sequence[str], limit: int
) -> list[Snippet]:
    """The snippets placed on the caller's canvas whose display text matches, oldest placement first.
    No terms match every snippet."""
    candidates = (
        select(Snippet)
        .join(SnippetPlacement, SnippetPlacement.snippet_id == Snippet.id)
        .join(Canvas, Canvas.id == SnippetPlacement.canvas_id)
        .where(SnippetPlacement.canvas_id == canvas_id, Canvas.owner_id == owner_id, terms_filter(Snippet.raw_text, terms))
        .order_by(SnippetPlacement.created_at, SnippetPlacement.id)
    )
    rows = await _confirmed(db, candidates, lambda row: matches_terms(terms, row[0].raw_text), limit)
    return [row[0] for row in rows]
