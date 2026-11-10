"""Placement routes: remove a snippet from one canvas, and move / also show snippets on another
canvas (docs/features/snippet_placements.md#api)."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.api.placement_errors import placement_errors, placement_http_error
from app.api.tag_errors import owner_id
from app.db.database import get_db
from app.models.user import User
from app.schemas.placement import RemovePlacementResponse, TransferRequest, TransferResponse
from app.services.placements.errors import PlacementError
from app.services.placements.removal import remove_from_canvas
from app.services.placements.transfer import transfer

router = APIRouter(tags=["snippets"])


@router.delete(
    "/api/canvases/{canvas_id}/snippets/{snippet_id}",
    response_model=RemovePlacementResponse,
    responses=placement_errors(404),
)
async def remove_snippet_from_canvas(
    canvas_id: uuid.UUID,
    snippet_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Take the snippet off this canvas; when that was its last canvas it is deleted for good."""
    try:
        deleted = await remove_from_canvas(db, canvas_id, snippet_id, owner_id(user))
    except PlacementError as exc:
        raise placement_http_error(exc) from exc
    return RemovePlacementResponse(snippet_deleted=deleted)


@router.post(
    "/api/canvases/{canvas_id}/snippets/transfer",
    response_model=TransferResponse,
    responses=placement_errors(404, 422),
)
async def transfer_snippets(
    canvas_id: uuid.UUID,
    req: TransferRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Place snippets of this canvas on ``target_canvas_id`` (same position and size, stacked on
    top, ungrouped); ``move`` also removes them from this canvas. All or nothing."""
    try:
        result = await transfer(db, canvas_id, req.target_canvas_id, req.snippet_ids, req.mode, owner_id(user))
    except PlacementError as exc:
        raise placement_http_error(exc) from exc
    return TransferResponse(
        target_canvas_id=str(result.target_canvas_id),
        placed=[str(i) for i in result.placed],
        already_on_target=[str(i) for i in result.already_on_target],
        removed_from_source=result.removed_from_source,
    )
