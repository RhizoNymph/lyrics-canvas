"""Client-chosen ids on creates (docs/features/sync_versions.md#client-ids).

An offline client picks the UUID of what it creates, so a create can be retried safely:

* no row has the id: create the entity with it;
* a row has it, belongs to the caller and matches the create's payload: return that row with the
  normal success status (the retry of a create whose response was lost);
* otherwise (another user's row, or a different payload): ``IdConflictError``, nothing written.

"Matches the payload" compares what the create stores from the request: a canvas's title; a
snippet's text, type, grid mode, beats, derived_from, the grid when one was sent, and a placement
on the requested canvas (layout is last-write-wins and not compared); a tag's name and color; a
merge's snippet on the canvas with none of the requested originals still there. Word bank adds
resolve their ids inside app/services/word_bank/service.py ``add_words``.

A concurrent create with the same id loses the primary-key race with an IntegrityError (or, for
tags, ``TagNameTakenError``, which wraps every integrity failure on commit); it is resolved again
as a retry.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Sequence
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement
from app.models.snippet import GridMode, Snippet, SnippetType
from app.models.tag import Tag
from app.services import canvas_service, snippet_service, tag_service
from app.services.canvas_title import CanvasTitle
from app.services.placements.queries import placed_snippet
from app.services.placements.view import PlacedSnippet
from app.services.sync.errors import IdConflictError
from app.services.tag_errors import TagNameTakenError
from app.services.tag_service import TagSummary
from app.services.tag_values import TagColor, TagName

logger = logging.getLogger(__name__)


def _taken(kind: str, entity_id: uuid.UUID) -> IdConflictError:
    logger.info("sync.id_conflict kind=%s id=%s", kind, entity_id)
    return IdConflictError(f"{kind} id {entity_id} is already used by a different {kind}")


# ---- canvases ----


async def _resolve_canvas(db: AsyncSession, canvas_id: uuid.UUID, owner_id: uuid.UUID, title: CanvasTitle) -> Canvas | None:
    canvas = (await db.execute(select(Canvas).where(Canvas.id == canvas_id))).scalar_one_or_none()
    if canvas is None:
        return None
    if canvas.owner_id != owner_id or canvas.title != title:
        raise _taken("canvas", canvas_id)
    return canvas


async def create_canvas(db: AsyncSession, owner_id: str, title: CanvasTitle, canvas_id: uuid.UUID) -> Canvas:
    owner = uuid.UUID(owner_id)
    existing = await _resolve_canvas(db, canvas_id, owner, title)
    if existing is not None:
        return existing
    try:
        return await canvas_service.create_canvas(db, owner_id, title, canvas_id=canvas_id)
    except IntegrityError:
        await db.rollback()
        existing = await _resolve_canvas(db, canvas_id, owner, title)
        if existing is None:
            raise
        return existing


# ---- snippets ----


@dataclass(frozen=True, slots=True)
class SnippetCreateContent:
    """What a snippet create stores from its request, for comparing a retry with the stored row.
    ``grid_data`` is the grid the client sent (None when the server derived it from the text)."""

    raw_text: str
    snippet_type: SnippetType
    grid_mode: GridMode
    beats_per_line: int | None
    derived_from: dict | None
    grid_data: dict | None


def _same_content(snippet: Snippet, content: SnippetCreateContent) -> bool:
    return (
        snippet.raw_text == content.raw_text
        and snippet.snippet_type == content.snippet_type
        and snippet.grid_mode == content.grid_mode
        and snippet.beats_per_line == content.beats_per_line
        and snippet.derived_from == content.derived_from
        and (content.grid_data is None or snippet.grid_data == content.grid_data)
    )


async def _resolve_snippet(
    db: AsyncSession, snippet_id: uuid.UUID, canvas_id: uuid.UUID, owner_id: uuid.UUID, content: SnippetCreateContent
) -> PlacedSnippet | None:
    result = await db.execute(select(Snippet).where(Snippet.id == snippet_id).execution_options(populate_existing=True))
    snippet = result.scalar_one_or_none()
    if snippet is None:
        return None
    if snippet.owner_id != owner_id or not _same_content(snippet, content):
        raise _taken("snippet", snippet_id)
    placed = await placed_snippet(db, snippet_id, canvas_id, owner_id)
    if placed is None:
        raise _taken("snippet", snippet_id)
    return placed


async def create_snippet(
    db: AsyncSession,
    canvas_id: str,
    owner_id: str,
    snippet_id: uuid.UUID,
    content: SnippetCreateContent,
    **fields,
) -> PlacedSnippet | None:
    """``snippet_service.create_snippet`` with a client id; None when the canvas is not the caller's."""
    cid, owner = uuid.UUID(canvas_id), uuid.UUID(owner_id)
    existing = await _resolve_snippet(db, snippet_id, cid, owner, content)
    if existing is not None:
        return existing
    try:
        return await snippet_service.create_snippet(db, canvas_id, owner_id, snippet_id=snippet_id, **fields)
    except IntegrityError:
        await db.rollback()
        existing = await _resolve_snippet(db, snippet_id, cid, owner, content)
        if existing is None:
            raise
        return existing


# ---- tags ----


async def _resolve_tag(db: AsyncSession, tag_id: uuid.UUID, owner_id: uuid.UUID, name: TagName, color: TagColor) -> TagSummary | None:
    tag = (await db.execute(select(Tag).where(Tag.id == tag_id).execution_options(populate_existing=True))).scalar_one_or_none()
    if tag is None:
        return None
    if tag.user_id != owner_id or tag.name != name or tag.color != color:
        raise _taken("tag", tag_id)
    return await tag_service.summary(db, tag_id)


async def create_tag(db: AsyncSession, owner_id: uuid.UUID, name: TagName, color: TagColor, tag_id: uuid.UUID) -> TagSummary:
    """``tag_service.create_tag`` with a client id. A name already taken by another tag keeps its
    ``tag_name_taken`` error."""
    existing = await _resolve_tag(db, tag_id, owner_id, name, color)
    if existing is not None:
        return existing
    try:
        return await tag_service.create_tag(db, owner_id, name, color, tag_id=tag_id)
    except TagNameTakenError:
        existing = await _resolve_tag(db, tag_id, owner_id, name, color)
        if existing is None:
            raise
        return existing


# ---- merge ----


async def resolve_merge(
    db: AsyncSession, canvas_id: uuid.UUID, owner_id: uuid.UUID, merged_id: uuid.UUID, snippet_ids: Sequence[uuid.UUID]
) -> PlacedSnippet | None:
    """The merged snippet of an earlier identical merge, or None when no row has ``merged_id``.

    The originals are gone from this canvas after a merge, so a retry is recognized by the merged
    snippet being the caller's, placed on this canvas, while none of ``snippet_ids`` is."""
    owner = (await db.execute(select(Snippet.owner_id).where(Snippet.id == merged_id))).scalar_one_or_none()
    if owner is None:
        return None
    if owner != owner_id:
        raise _taken("snippet", merged_id)
    placed = await placed_snippet(db, merged_id, canvas_id, owner_id)
    remaining = await db.execute(
        select(SnippetPlacement.snippet_id).where(
            SnippetPlacement.canvas_id == canvas_id, SnippetPlacement.snippet_id.in_(list(snippet_ids))
        ).limit(1)
    )
    if placed is None or remaining.first() is not None:
        raise _taken("snippet", merged_id)
    return placed
