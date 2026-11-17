"""Server-assigned list ranks (docs/features/snippet_placements.md#list-order).

New placements go to the top of the canvas's custom order: ``key_between(None, min rank)``.
Two writers may pick the same key concurrently; ties are allowed and order by placement id.
"""

from __future__ import annotations

import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.placement import SnippetPlacement
from app.services.placements.list_rank import key_between, n_keys_between, validate_rank


async def min_rank(db: AsyncSession, canvas_id: uuid.UUID) -> str | None:
    """The smallest rank on the canvas (byte order, the column is ``COLLATE "C"``); None when empty."""
    result = await db.execute(select(func.min(SnippetPlacement.list_rank)).where(SnippetPlacement.canvas_id == canvas_id))
    return result.scalar_one_or_none()


async def top_ranks(db: AsyncSession, canvas_id: uuid.UUID, n: int) -> list[str]:
    """``n`` ascending ranks above everything on the canvas."""
    return n_keys_between(None, await min_rank(db, canvas_id), n)


async def top_rank(db: AsyncSession, canvas_id: uuid.UUID) -> str:
    return key_between(None, await min_rank(db, canvas_id))


async def rank_for_new(db: AsyncSession, canvas_id: uuid.UUID, requested: str | None) -> str:
    """A client-chosen rank (validated, ``InvalidListRankError``) or the top of the canvas."""
    if requested is not None:
        return validate_rank(requested)
    return await top_rank(db, canvas_id)
