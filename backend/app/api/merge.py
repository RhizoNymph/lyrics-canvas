import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.canvases import _snippet_response
from app.api.deps import get_current_user
from app.api.sync_errors import conflict_responses, id_conflict_http_error, version_conflict_http_error
from app.api.tag_errors import owner_id
from app.api.snippets import _compute_size
from app.db.database import get_db
from app.models.user import User
from app.schemas.merge import MergeRequest, MergeResponse
from app.schemas.snippet import GridDataModel
from app.schemas.sync_errors import IdConflictResponse, SnippetsVersionConflictResponse
from app.services import merge_service, recording_service, snippet_service
from app.services.merge_service import MergePersistError, MergeSource, MergeSourcesNotFoundError, TooFewSnippetsError
from app.services.phonetics_service import compute_snippet_phonetics
from app.services.sync import client_ids, versions
from app.services.sync.errors import IdConflictError, VersionConflictError

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


async def _earlier_merge(db: AsyncSession, canvas_id: uuid.UUID, user: User, req: MergeRequest) -> MergeResponse | None:
    """The response to a retried merge (``merged_id`` already holds its result): the merged
    snippet, and the requested originals as ``deleted_ids`` (the first response's merge order is
    not stored). None when nothing has that id yet."""
    if req.merged_id is None:
        return None
    try:
        merged = await client_ids.resolve_merge(db, canvas_id, owner_id(user), req.merged_id, req.snippet_ids)
    except IdConflictError as exc:
        raise id_conflict_http_error(exc) from exc
    if merged is None:
        return None
    return MergeResponse(snippet=_snippet_response(merged), deleted_ids=[str(i) for i in req.snippet_ids])


@router.post(
    "/api/canvases/{canvas_id}/merge", response_model=MergeResponse,
    responses=conflict_responses(SnippetsVersionConflictResponse, IdConflictResponse),
)
async def merge_snippets(
    canvas_id: uuid.UUID,
    req: MergeRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Replace two or more snippets on this canvas with one merged snippet (see
    docs/features/merge_blocks.md). ``deleted_ids`` are the originals taken off this canvas; one
    also placed on other canvases stays there unchanged with its recordings.

    ``merged_id`` (client-chosen) names the merged snippet; retrying the identical merge returns it
    again, and an id used by any other snippet is 409 ``id_conflict``. ``base_versions``
    ({original id: version}) with any stale original is 409 ``version_conflict`` (``current``
    lists the stale originals on this canvas); nothing changes."""
    earlier = await _earlier_merge(db, canvas_id, user, req)
    if earlier is not None:
        return earlier
    originals = await snippet_service.get_canvas_snippets(db, canvas_id, str(user.id), req.snippet_ids)
    try:
        found = {str(s.id) for s in originals}
        missing = [str(i) for i in req.snippet_ids if str(i) not in found]
        if missing:
            raise MergeSourcesNotFoundError(missing)
        order = {snippet_id: i for i, snippet_id in enumerate(req.snippet_ids)}
        versions.check_originals(sorted(originals, key=lambda s: order[s.id]), req.base_versions or {})
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
            # recordings of originals deleted by the merge follow their lines into the merged snippet
            reparent=recording_service.reassign_recordings,
            **({} if req.merged_id is None else {"merged_id": req.merged_id}),
        )
    except VersionConflictError as exc:
        raise version_conflict_http_error(exc, _snippet_response) from exc
    except MergeSourcesNotFoundError as exc:
        if req.merged_id is not None:
            # a concurrent identical merge may have taken the originals first
            await db.rollback()
            earlier = await _earlier_merge(db, canvas_id, user, req)
            if earlier is not None:
                return earlier
        raise HTTPException(status_code=404, detail="Snippet not found") from exc
    except TooFewSnippetsError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except MergePersistError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    return MergeResponse(snippet=_snippet_response(created), deleted_ids=list(merged.source_ids))
