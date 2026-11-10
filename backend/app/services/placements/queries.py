"""Loading snippets through their placements, and the ownership and group checks.

Canvas-scoped reads join the placement's canvas and check ``Canvas.owner_id``; snippet-scoped
reads check ``Snippet.owner_id``. Loads use ``populate_existing`` so rows changed by bulk
statements earlier in the session are read fresh.
"""

from __future__ import annotations

import uuid
from collections.abc import Iterable, Sequence

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.canvas import Canvas
from app.models.group import Group
from app.models.placement import SnippetPlacement
from app.models.snippet import Snippet
from app.services.placements.errors import GroupNotFoundError
from app.services.placements.view import CanvasRef, PlacedSnippet, sort_canvas_refs


async def owned_canvas(db: AsyncSession, canvas_id: uuid.UUID, owner_id: uuid.UUID) -> Canvas | None:
    result = await db.execute(select(Canvas).where(Canvas.id == canvas_id, Canvas.owner_id == owner_id))
    return result.scalar_one_or_none()


async def canvas_refs(db: AsyncSession, snippet_ids: Iterable[uuid.UUID]) -> dict[uuid.UUID, tuple[CanvasRef, ...]]:
    """Every canvas holding each snippet, sorted by ``title.lower()`` then id."""
    ids = list(dict.fromkeys(snippet_ids))
    if not ids:
        return {}
    result = await db.execute(
        select(SnippetPlacement.snippet_id, Canvas.id, Canvas.title)
        .join(Canvas, Canvas.id == SnippetPlacement.canvas_id)
        .where(SnippetPlacement.snippet_id.in_(ids))
    )
    found: dict[uuid.UUID, list[CanvasRef]] = {}
    for snippet_id, canvas_id, title in result.all():
        found.setdefault(snippet_id, []).append(CanvasRef(id=canvas_id, title=title))
    return {snippet_id: sort_canvas_refs(refs) for snippet_id, refs in found.items()}


def _placed_query():
    return (
        select(SnippetPlacement, Snippet)
        .join(Snippet, Snippet.id == SnippetPlacement.snippet_id)
        .join(Canvas, Canvas.id == SnippetPlacement.canvas_id)
        .options(selectinload(Snippet.tags))
        .execution_options(populate_existing=True)
    )


async def _with_refs(db: AsyncSession, rows: Sequence[tuple[SnippetPlacement, Snippet]]) -> list[PlacedSnippet]:
    refs = await canvas_refs(db, (snippet.id for _, snippet in rows))
    return [PlacedSnippet(snippet=snippet, placement=placement, canvases=refs.get(snippet.id, ())) for placement, snippet in rows]


async def placed_on_canvas(db: AsyncSession, canvas_id: uuid.UUID) -> list[PlacedSnippet]:
    """Every snippet placed on the canvas (the caller has checked access), oldest placement first."""
    result = await db.execute(
        _placed_query()
        .where(SnippetPlacement.canvas_id == canvas_id)
        .order_by(SnippetPlacement.created_at, SnippetPlacement.id)
    )
    return await _with_refs(db, [tuple(row) for row in result.all()])


async def placed_snippets(
    db: AsyncSession, canvas_id: uuid.UUID, owner_id: uuid.UUID, snippet_ids: Sequence[uuid.UUID], *, lock: bool = False
) -> list[PlacedSnippet]:
    """The requested snippets placed on the caller's canvas. Missing, foreign or not-placed ids are
    absent from the result. ``lock`` takes the snippet rows FOR UPDATE (see removal.lock_snippets)."""
    ids = list(dict.fromkeys(snippet_ids))
    if not ids:
        return []
    query = _placed_query().where(
        SnippetPlacement.canvas_id == canvas_id,
        SnippetPlacement.snippet_id.in_(ids),
        Canvas.owner_id == owner_id,
    )
    if lock:
        query = query.with_for_update(of=Snippet)
    result = await db.execute(query)
    return await _with_refs(db, [tuple(row) for row in result.all()])


async def placed_snippet(
    db: AsyncSession, snippet_id: uuid.UUID, canvas_id: uuid.UUID, owner_id: uuid.UUID, *, lock: bool = False
) -> PlacedSnippet | None:
    found = await placed_snippets(db, canvas_id, owner_id, [snippet_id], lock=lock)
    return found[0] if found else None


async def placed_by_placement_id(db: AsyncSession, placement_id: uuid.UUID) -> PlacedSnippet:
    """Reload one placement with its snippet (after a commit); it must exist."""
    result = await db.execute(_placed_query().where(SnippetPlacement.id == placement_id))
    placement, snippet = result.one()
    return (await _with_refs(db, [(placement, snippet)]))[0]


async def ensure_group_on_canvas(db: AsyncSession, group_id: uuid.UUID, canvas_id: uuid.UUID) -> None:
    """A placement's group must be a group of the placement's canvas."""
    result = await db.execute(select(Group.id).where(Group.id == group_id, Group.canvas_id == canvas_id))
    if result.scalar_one_or_none() is None:
        raise GroupNotFoundError(f"group {group_id} is not on canvas {canvas_id}")
