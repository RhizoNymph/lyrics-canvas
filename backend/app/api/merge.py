import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.canvases import _snippet_response
from app.api.deps import get_current_user
from app.api.snippets import _compute_size
from app.db.database import get_db
from app.models.user import User
from app.schemas.merge import MergeRequest, MergeResponse
from app.schemas.snippet import GridDataModel
from app.services import merge_service, snippet_service
from app.services.merge_service import MergePersistError, MergeSource, MergeSourcesNotFoundError, TooFewSnippetsError
from app.services.phonetics_service import compute_snippet_phonetics

router = APIRouter(tags=["snippets"])


def _merge_source(s) -> MergeSource:
    return MergeSource(
        id=str(s.id),
        raw_text=s.raw_text,
        grid=GridDataModel.model_validate(s.grid_data) if s.grid_data is not None else None,
        snippet_type=s.snippet_type,
        grid_mode=s.grid_mode,
        beats_per_line=s.beats_per_line,
        group_id=str(s.group_id) if s.group_id else None,
        position_x=s.position_x,
        position_y=s.position_y,
        tag_ids=tuple(str(t.id) for t in (s.tags or [])),
    )


@router.post("/api/canvases/{canvas_id}/merge", response_model=MergeResponse)
async def merge_snippets(
    canvas_id: uuid.UUID,
    req: MergeRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Replace two or more snippets with one merged snippet (see docs/features/merge_blocks.md)."""
    originals = await snippet_service.get_canvas_snippets(db, canvas_id, str(user.id), req.snippet_ids)
    try:
        found = {str(s.id) for s in originals}
        missing = [str(i) for i in req.snippet_ids if str(i) not in found]
        if missing:
            raise MergeSourcesNotFoundError(missing)
        merged = merge_service.merge_snippets([_merge_source(s) for s in originals], req.mode)
        grid_data = merged.grid.model_dump(mode="json")
        # Phonetics follow the merged grid exactly as on create: grid stress wins via overrides.
        phonetics = compute_snippet_phonetics(merged.raw_text, grid_data, None).model_dump(mode="json")
        width, height = _compute_size(grid_data)
        tags_by_id = {str(t.id): t for s in originals for t in (s.tags or [])}
        by_id = {str(s.id): s for s in originals}
        created = await snippet_service.replace_with_merged(
            db,
            canvas_id,
            {
                "snippet_type": merged.snippet_type,
                "grid_mode": merged.grid_mode,
                "beats_per_line": merged.beats_per_line,
                "grid_data": grid_data,
                "raw_text": merged.raw_text,
                "position_x": merged.position_x,
                "position_y": merged.position_y,
                "width": width,
                "height": height,
                "group_id": uuid.UUID(merged.group_id) if merged.group_id else None,
                "phonetics": phonetics,
            },
            [tags_by_id[t] for t in merged.tag_ids],
            [by_id[i] for i in merged.source_ids],
        )
    except MergeSourcesNotFoundError as exc:
        raise HTTPException(status_code=404, detail="Snippet not found") from exc
    except TooFewSnippetsError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except MergePersistError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return MergeResponse(snippet=_snippet_response(created), deleted_ids=list(merged.source_ids))
