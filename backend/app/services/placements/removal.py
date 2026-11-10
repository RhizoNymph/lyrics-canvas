"""Removing placements while keeping the invariant: every snippet has at least one placement.

Every path that removes placements (canvas delete, remove from canvas, move, merge) locks the
affected snippet rows first (``lock_snippets``, id order, so concurrent removals serialize instead
of each seeing "another placement left") and calls ``delete_orphans`` in the same transaction.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Iterable

from sqlalchemy import delete, exists, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.placement import SnippetPlacement
from app.models.snippet import Snippet
from app.services.placements.errors import PlacementNotFoundError
from app.services.placements.queries import placed_snippet

logger = logging.getLogger(__name__)


async def lock_snippets(db: AsyncSession, snippet_ids: Iterable[uuid.UUID]) -> None:
    ids = sorted(set(snippet_ids), key=str)
    if ids:
        await db.execute(select(Snippet.id).where(Snippet.id.in_(ids)).order_by(Snippet.id).with_for_update())


async def delete_orphans(db: AsyncSession, snippet_ids: Iterable[uuid.UUID]) -> list[uuid.UUID]:
    """Delete those of ``snippet_ids`` left with no placement (recordings, tag links and
    embeddings go by the FK cascades). Returns the deleted ids."""
    ids = list(set(snippet_ids))
    if not ids:
        return []
    result = await db.execute(
        delete(Snippet)
        .where(Snippet.id.in_(ids), ~exists().where(SnippetPlacement.snippet_id == Snippet.id))
        .returning(Snippet.id)
        .execution_options(synchronize_session=False)
    )
    return list(result.scalars().all())


async def remove_placements(db: AsyncSession, canvas_id: uuid.UUID, snippet_ids: Iterable[uuid.UUID]) -> list[uuid.UUID]:
    """Delete the placements of ``snippet_ids`` on the canvas, then the snippets left with none.
    The caller has locked the snippets and commits. Returns the deleted snippet ids."""
    ids = list(set(snippet_ids))
    if not ids:
        return []
    await db.execute(
        delete(SnippetPlacement)
        .where(SnippetPlacement.canvas_id == canvas_id, SnippetPlacement.snippet_id.in_(ids))
        .execution_options(synchronize_session=False)
    )
    return await delete_orphans(db, ids)


async def remove_from_canvas(db: AsyncSession, canvas_id: uuid.UUID, snippet_id: uuid.UUID, owner_id: uuid.UUID) -> bool:
    """DELETE /api/canvases/{cid}/snippets/{sid}. True when that was the last placement and the
    snippet is gone; ``PlacementNotFoundError`` when it is not placed on the caller's canvas."""
    if await placed_snippet(db, snippet_id, canvas_id, owner_id, lock=True) is None:
        raise PlacementNotFoundError(f"snippet {snippet_id} is not on canvas {canvas_id}")
    deleted = await remove_placements(db, canvas_id, [snippet_id])
    await db.commit()
    logger.info(
        "placement.removed canvas_id=%s snippet_id=%s snippet_deleted=%s", canvas_id, snippet_id, bool(deleted)
    )
    return bool(deleted)


async def delete_everywhere(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID) -> bool:
    """DELETE /api/snippets/{id}: the snippet with all its placements and recordings. False when
    the caller owns no such snippet."""
    result = await db.execute(
        delete(Snippet)
        .where(Snippet.id == snippet_id, Snippet.owner_id == owner_id)
        .returning(Snippet.id)
        .execution_options(synchronize_session=False)
    )
    deleted = result.scalar_one_or_none() is not None
    await db.commit()
    logger.info("snippet.deleted_everywhere snippet_id=%s deleted=%s", snippet_id, deleted)
    return deleted
