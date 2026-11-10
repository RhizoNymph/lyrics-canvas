import uuid

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.api.deps import get_current_user
from app.api.canvases import _snippet_response
from app.api.placement_errors import placement_errors, placement_http_error
from app.models.user import User
from app.schemas.phonetics import SnippetPhonetics
from app.schemas.snippet import BatchPositionResult, BatchPositionUpdate, SnippetCreate, SnippetResponse, SnippetUpdate
from app.services import snippet_service
from app.services.placements.errors import GroupNotFoundError, PlacementError, PlacementNotFoundError
from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics_service import compute_snippet_phonetics
from app.services.snippet_content import compute_size, derive_new_content

router = APIRouter(tags=["snippets"])

# Kept under the old name for existing importers; the implementation lives in snippet_content.
_compute_size = compute_size


def _group_uuid(group_id: str | None) -> uuid.UUID | None:
    if not group_id:
        return None
    try:
        return uuid.UUID(group_id)
    except ValueError as exc:
        raise placement_http_error(GroupNotFoundError(f"group {group_id!r} not found")) from exc


@router.post(
    "/api/canvases/{canvas_id}/snippets", response_model=SnippetResponse, status_code=201, responses=placement_errors(404)
)
async def create_snippet(
    canvas_id: str,
    req: SnippetCreate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    # Python-mode dump keeps snippet_type/grid_mode as enums; the ORM object is
    # returned un-expired after commit, so a json-mode dump would leak plain
    # strings into the response serializer.
    data = req.model_dump()
    raw_text = data.pop("raw_text", "")
    # A client-supplied grid (block merging) is stored as-is so its rests and stress survive;
    # otherwise the text is syllabified. Either way phonetics follow the stored grid.
    data.pop("grid_data", None)
    content = derive_new_content(
        raw_text, req.grid_data.model_dump(exclude_unset=True) if req.grid_data is not None else None
    )
    data["group_id"] = _group_uuid(data.pop("group_id", None))
    data["width"] = content.width
    data["height"] = content.height
    try:
        snippet = await snippet_service.create_snippet(
            db, canvas_id, str(user.id), raw_text=raw_text, grid_data=content.grid_data, phonetics=content.phonetics, **data
        )
    except PlacementError as exc:
        raise placement_http_error(exc) from exc
    if not snippet:
        raise HTTPException(status_code=404, detail="Canvas not found")
    return _snippet_response(snippet)


@router.put("/api/snippets/{snippet_id}", response_model=SnippetResponse, responses=placement_errors(404))
async def update_snippet(
    snippet_id: str,
    req: SnippetUpdate,
    canvas_id: uuid.UUID = Query(..., description="the canvas whose placement takes the layout fields; the response is in its context"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Content fields update the shared snippet; layout fields (position, size, z_index,
    group_id) update the placement on ``canvas_id``."""
    data = req.model_dump(exclude_none=True)
    if "group_id" in data:
        data["group_id"] = _group_uuid(data["group_id"])
    # What the client sent decides content_updated_at; the grid and phonetics derived below do not.
    content_edits = {key: value for key, value in data.items() if key in snippet_service.CONTENT_FIELDS}
    # Re-syllabify if raw_text changed and no explicit grid_data
    if "raw_text" in data and "grid_data" not in data:
        data["grid_data"] = syllabify_text(data["raw_text"])
    # Recompute phonetics alongside the grid; grid stress wins via overrides
    if "raw_text" in data:
        data["phonetics"] = compute_snippet_phonetics(data["raw_text"], data["grid_data"], None).model_dump(mode="json")
    elif "grid_data" in data:
        existing = await snippet_service.get_owned_snippet(db, snippet_id, str(user.id))
        if not existing:
            raise placement_http_error(PlacementNotFoundError(f"snippet {snippet_id} is not on canvas {canvas_id}"))
        overrides = SnippetPhonetics.model_validate(existing.phonetics).overrides if existing.phonetics else None
        data["phonetics"] = compute_snippet_phonetics(existing.raw_text, data["grid_data"], overrides).model_dump(mode="json")
    # Auto-resize based on grid content
    if "grid_data" in data:
        width, height = _compute_size(data["grid_data"])
        data["width"] = width
        data["height"] = height
    try:
        snippet = await snippet_service.update_snippet(
            db, snippet_id, str(user.id), canvas_id=canvas_id, content_edits=content_edits, **data
        )
    except PlacementError as exc:
        raise placement_http_error(exc) from exc
    return _snippet_response(snippet)


@router.delete("/api/snippets/{snippet_id}", status_code=204)
async def delete_snippet(
    snippet_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Delete everywhere: the snippet, all its placements and its recordings."""
    if not await snippet_service.delete_snippet(db, str(snippet_id), str(user.id)):
        raise HTTPException(status_code=404, detail="Snippet not found")


@router.patch("/api/snippets/batch-position", response_model=BatchPositionResult)
async def batch_position(
    req: BatchPositionUpdate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    updates = [u.model_dump() for u in req.updates]
    count = await snippet_service.batch_update_positions(db, req.canvas_id, updates, str(user.id))
    return BatchPositionResult(updated=count)
