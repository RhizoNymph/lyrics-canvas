import uuid

from fastapi import APIRouter, Depends, HTTPException, BackgroundTasks
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.api.deps import get_current_user
from app.api.canvases import _snippet_response
from app.models.user import User
from app.schemas.phonetics import SnippetPhonetics
from app.schemas.snippet import BatchPositionResult, BatchPositionUpdate, SnippetCreate, SnippetResponse, SnippetUpdate
from app.services import snippet_service
from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics_service import compute_snippet_phonetics

router = APIRouter(tags=["snippets"])

# Must match frontend SnippetCard constants
CELL_WIDTH = 60
CELL_HEIGHT = 22
PADDING = 12
HEADER_HEIGHT = 28
MIN_WIDTH = 200
MIN_HEIGHT = 80


def _compute_size(grid_data: dict) -> tuple[float, float]:
    """Compute snippet width/height from grid data.

    Width = (max_cells + 1) * CELL_WIDTH + padding  ("+1" leaves room for one more syllable)
    Height = num_lines * CELL_HEIGHT + header + padding
    Floats, as the columns store them, so a fresh snippet serializes like a reloaded one.
    """
    lines = grid_data.get("lines", [])
    if not lines:
        return (float(MIN_WIDTH), float(MIN_HEIGHT))
    max_cells = max((len(line.get("cells", [])) for line in lines), default=0)
    width = max((max_cells + 1) * CELL_WIDTH + PADDING * 2 + 20, MIN_WIDTH)  # +20 for syllable count column
    height = max(len(lines) * CELL_HEIGHT + HEADER_HEIGHT + PADDING * 2, MIN_HEIGHT)
    return (float(width), float(height))


@router.post("/api/canvases/{canvas_id}/snippets", response_model=SnippetResponse, status_code=201)
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
    if req.grid_data is not None:
        grid_data = req.grid_data.model_dump(exclude_unset=True)
    else:
        grid_data = syllabify_text(raw_text) if raw_text else {"lines": []}
    phonetics = compute_snippet_phonetics(raw_text, grid_data, None).model_dump(mode="json")
    group_id = data.pop("group_id", None)
    data["group_id"] = uuid.UUID(group_id) if group_id else None
    width, height = _compute_size(grid_data)
    data["width"] = width
    data["height"] = height
    snippet = await snippet_service.create_snippet(
        db, canvas_id, str(user.id), raw_text=raw_text, grid_data=grid_data, phonetics=phonetics, **data
    )
    if not snippet:
        raise HTTPException(status_code=404, detail="Canvas not found")
    return _snippet_response(snippet)


@router.put("/api/snippets/{snippet_id}", response_model=SnippetResponse)
async def update_snippet(
    snippet_id: str,
    req: SnippetUpdate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    data = req.model_dump(exclude_none=True)
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
            raise HTTPException(status_code=404, detail="Snippet not found")
        overrides = SnippetPhonetics.model_validate(existing.phonetics).overrides if existing.phonetics else None
        data["phonetics"] = compute_snippet_phonetics(existing.raw_text, data["grid_data"], overrides).model_dump(mode="json")
    # Auto-resize based on grid content
    if "grid_data" in data:
        width, height = _compute_size(data["grid_data"])
        data["width"] = width
        data["height"] = height
    snippet = await snippet_service.update_snippet(db, snippet_id, str(user.id), content_edits=content_edits, **data)
    if not snippet:
        raise HTTPException(status_code=404, detail="Snippet not found")
    return _snippet_response(snippet)


@router.delete("/api/snippets/{snippet_id}", status_code=204)
async def delete_snippet(
    snippet_id: str,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if not await snippet_service.delete_snippet(db, snippet_id, str(user.id)):
        raise HTTPException(status_code=404, detail="Snippet not found")


@router.patch("/api/snippets/batch-position", response_model=BatchPositionResult)
async def batch_position(
    req: BatchPositionUpdate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    count = await snippet_service.batch_update_positions(db, req.updates, str(user.id))
    return BatchPositionResult(updated=count)
