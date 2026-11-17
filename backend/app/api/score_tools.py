"""Score drafts and exports (docs/features/score_draft_export.md, round-4 contract section 5).

``POST /api/snippets/{snippet_id}/score/draft`` builds a score from the grid or from a voice take
without saving it; ``GET /api/snippets/{snippet_id}/score/export?format=midi|musicxml`` downloads
the stored score. Both need the snippet's owner (like the other snippet routes); share-token views
have neither.

Errors: ``HTTPException(status, {"error": code, "message"})`` for the tool errors, and the shared
``422 {"error": "invalid_score", "reason", "line"?, "note"?}`` for bad meter / bpm / note values.
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Body, Depends, HTTPException, Query
from fastapi.responses import Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.db.database import get_db
from app.models.user import User
from app.schemas.score_tools import (
    GridDraftRequest,
    InvalidScoreResponse,
    RecordingDraftRequest,
    ScoreDraftRequest,
    ScoreDraftResponse,
    ScoreToolErrorResponse,
)
from app.services.score import ScoreInvalid
from app.services.score.wire import ScoreModel
from app.services.score_tools import service
from app.services.score_tools.errors import ScoreToolError
from app.services.score_tools.service import ExportFormat, GridDraft, RecordingDraft

router = APIRouter(tags=["score"])


def _owner(user: User) -> uuid.UUID:
    return user.id if isinstance(user.id, uuid.UUID) else uuid.UUID(str(user.id))


def tool_http_error(exc: ScoreToolError) -> HTTPException:
    return HTTPException(status_code=exc.status, detail={"error": exc.code.value, "message": exc.message})


def invalid_score_http_error(exc: ScoreInvalid) -> HTTPException:
    return HTTPException(status_code=422, detail=exc.as_dict())


def _errors(statuses: dict[int, type]) -> dict[int | str, dict[str, object]]:
    return {status: {"model": model} for status, model in statuses.items()}


def _request(body: GridDraftRequest | RecordingDraftRequest) -> service.DraftRequest:
    meter = body.meter.model_dump()
    match body:
        case GridDraftRequest():
            return GridDraft(meter=meter, bpm=body.bpm, note=body.note)
        case RecordingDraftRequest():
            return RecordingDraft(recording_id=body.recording_id, meter=meter, bpm=body.bpm)


@router.post(
    "/api/snippets/{snippet_id}/score/draft",
    response_model=ScoreDraftResponse,
    responses=_errors({404: ScoreToolErrorResponse, 422: ScoreToolErrorResponse | InvalidScoreResponse}),
)
async def draft_score(
    snippet_id: uuid.UUID,
    body: ScoreDraftRequest = Body(...),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ScoreDraftResponse:
    """404 ``snippet_not_found`` / ``recording_not_found``; 422 ``recording_has_no_timings``,
    ``draft_empty`` or ``invalid_score`` (``meter_invalid``, ``tempo_invalid``, ``snap_invalid``,
    ``exceeds_max_ticks``)."""
    try:
        result = await service.draft(db, snippet_id, _owner(user), _request(body))
    except ScoreToolError as exc:
        raise tool_http_error(exc) from exc
    except ScoreInvalid as exc:
        raise invalid_score_http_error(exc) from exc
    return ScoreDraftResponse(
        score=ScoreModel.from_score(result.score),
        bpm_estimated=result.bpm_estimated,
        unplaced=result.unplaced,
        unmatched_words=result.unmatched_words,
    )


@router.get(
    "/api/snippets/{snippet_id}/score/export",
    response_class=Response,
    responses={
        200: {"content": {"audio/midi": {}, "application/vnd.recordare.musicxml+xml": {}}, "description": "the file"},
        **_errors({404: ScoreToolErrorResponse, 500: ScoreToolErrorResponse}),
    },
)
async def export_score(
    snippet_id: uuid.UUID,
    export_format: ExportFormat = Query(..., alias="format"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    """A download (``Content-Disposition: attachment``). 404 ``snippet_not_found`` /
    ``score_not_found``; 500 ``stored_score_invalid`` (never expected)."""
    try:
        file = await service.export(db, snippet_id, _owner(user), export_format)
    except ScoreToolError as exc:
        raise tool_http_error(exc) from exc
    return Response(
        content=file.content,
        media_type=file.media_type,
        headers={"Content-Disposition": f'attachment; filename="{file.filename}"', "Cache-Control": "no-store"},
    )
