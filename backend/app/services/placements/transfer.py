"""POST /api/canvases/{cid}/snippets/transfer: move snippets to, or also show them on, another
canvas (docs/features/snippet_placements.md#transfer).

All or nothing: every check runs before the first write and the writes commit once.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.placement import SnippetPlacement
from app.services.placements.errors import CanvasNotFoundError, PlacementNotFoundError, SameCanvasError
from app.services.placements.queries import owned_canvas, placed_snippets
from app.services.placements.removal import lock_snippets, remove_placements
from app.services.placements.view import PlacedSnippet

logger = logging.getLogger(__name__)


class TransferMode(StrEnum):
    move = "move"  # place on the target, then remove from the source
    show = "show"  # place on the target, keep on the source


@dataclass(frozen=True, slots=True)
class TransferResult:
    target_canvas_id: uuid.UUID
    placed: tuple[uuid.UUID, ...]  # got a new placement on the target, in request order
    already_on_target: tuple[uuid.UUID, ...]  # kept their target placement untouched, in request order
    removed_from_source: bool


def stacking_order(sources: Sequence[PlacedSnippet]) -> list[PlacedSnippet]:
    """Source z order, ties by id: rank i lands at target max z + 1 + i."""
    return sorted(sources, key=lambda s: (s.z_index, str(s.id)))


def target_placements(sources: Sequence[PlacedSnippet], target_id: uuid.UUID, target_max_z: int | None) -> list[SnippetPlacement]:
    """New target placements: same position and size (the selection keeps its relative layout),
    stacked above everything on the target in source z order, ungrouped."""
    base = (target_max_z if target_max_z is not None else -1) + 1
    return [
        SnippetPlacement(
            snippet_id=source.id,
            canvas_id=target_id,
            position_x=source.position_x,
            position_y=source.position_y,
            width=source.width,
            height=source.height,
            z_index=base + rank,
            group_id=None,
        )
        for rank, source in enumerate(stacking_order(sources))
    ]


async def transfer(
    db: AsyncSession,
    source_id: uuid.UUID,
    target_id: uuid.UUID,
    snippet_ids: Sequence[uuid.UUID],
    mode: TransferMode,
    owner_id: uuid.UUID,
) -> TransferResult:
    if source_id == target_id:
        raise SameCanvasError("the target canvas is the source canvas")
    ids = list(dict.fromkeys(snippet_ids))
    for canvas_id in (source_id, target_id):
        if await owned_canvas(db, canvas_id, owner_id) is None:
            raise CanvasNotFoundError(f"canvas {canvas_id} not found")
    await lock_snippets(db, ids)
    found = {s.id: s for s in await placed_snippets(db, source_id, owner_id, ids)}
    missing = [i for i in ids if i not in found]
    if missing:
        raise PlacementNotFoundError(f"not on canvas {source_id}: {', '.join(str(i) for i in missing)}")

    on_target = set(
        (await db.execute(
            select(SnippetPlacement.snippet_id).where(
                SnippetPlacement.canvas_id == target_id, SnippetPlacement.snippet_id.in_(ids)
            )
        )).scalars().all()
    )
    to_place = [found[i] for i in ids if i not in on_target]
    max_z = (await db.execute(
        select(func.max(SnippetPlacement.z_index)).where(SnippetPlacement.canvas_id == target_id)
    )).scalar_one_or_none()
    for placement in target_placements(to_place, target_id, max_z):
        db.add(placement)
    await db.flush()
    removed = mode == TransferMode.move
    if removed:
        # every snippet now has its target placement, so none is orphaned; checked all the same
        await remove_placements(db, source_id, ids)
    await db.commit()
    result = TransferResult(
        target_canvas_id=target_id,
        placed=tuple(s.id for s in to_place),
        already_on_target=tuple(i for i in ids if i in on_target),
        removed_from_source=removed,
    )
    logger.info(
        "placement.transfer source=%s target=%s mode=%s placed=%d already_on_target=%d",
        source_id, target_id, mode.value, len(result.placed), len(result.already_on_target),
    )
    return result
