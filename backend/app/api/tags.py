"""User-wide tags and snippet links (docs/features/tag_expressions.md#http-api)."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.canvases import _snippet_response
from app.api.deps import get_current_user
from app.api.sync_errors import base_version_query, conflict_responses, id_conflict_http_error, version_conflict_http_error
from app.api.tag_errors import owner_id, tag_errors, tag_http_error
from app.db.database import get_db
from app.models.user import User
from app.schemas.sync_errors import IdConflictResponse, SnippetVersionConflictResponse, TagVersionConflictResponse
from app.schemas.tag import TagCreate, TagErrorResponse, TagLinkRequest, TagLinkResponse, TagResponse, TagUpdate
from app.services import tag_service
from app.services.sync import client_ids, versions
from app.services.sync.errors import IdConflictError, VersionConflictError
from app.services.tag_errors import TagServiceError
from app.services.tag_service import TagSummary
from app.services.tag_values import tag_color, tag_name

router = APIRouter(tags=["tags"])


def _tag_response(t: TagSummary) -> TagResponse:
    return TagResponse(
        id=str(t.id),
        name=t.name,
        color=t.color,
        snippet_count=t.snippet_count,
        word_count=t.word_count,
        created_at=str(t.created_at),
        version=t.version,
    )


@router.get("/api/tags", response_model=list[TagResponse])
async def list_tags(user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """The user's tags, sorted by name ignoring case."""
    return [_tag_response(t) for t in await tag_service.list_tags(db, owner_id(user))]


@router.post(
    "/api/tags", response_model=TagResponse, status_code=201,
    responses={**tag_errors(422), **conflict_responses(TagErrorResponse, IdConflictResponse)},
)
async def create_tag(req: TagCreate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """With ``id`` (client-chosen), retrying the identical create returns the same tag; an id used by
    a different tag is 409 ``id_conflict``. A taken name stays 409 ``tag_name_taken``."""
    try:
        name, color = tag_name(req.name), tag_color(req.color)
        match req.id:
            case None:
                tag = await tag_service.create_tag(db, owner_id(user), name, color)
            case client_id:
                tag = await client_ids.create_tag(db, owner_id(user), name, color, client_id)
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    except IdConflictError as exc:
        raise id_conflict_http_error(exc) from exc
    return _tag_response(tag)


@router.put(
    "/api/tags/{tag_id}", response_model=TagResponse,
    responses={**tag_errors(404, 422), **conflict_responses(TagErrorResponse, TagVersionConflictResponse)},
)
async def update_tag(
    tag_id: uuid.UUID, req: TagUpdate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)
):
    """Rename and/or recolor. A rename rewrites the user's presets that name the tag. A
    ``base_version`` that is not the tag's is 409 ``version_conflict``."""
    try:
        name = tag_name(req.name) if req.name is not None else None
        color = tag_color(req.color) if req.color is not None else None
        await versions.check_tag(db, tag_id, owner_id(user), req.base_version)
        tag = await tag_service.update_tag(db, tag_id, owner_id(user), name, color)
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    except VersionConflictError as exc:
        raise version_conflict_http_error(exc, _tag_response) from exc
    return _tag_response(tag)


@router.delete(
    "/api/tags/{tag_id}", status_code=204,
    responses={**tag_errors(404), **conflict_responses(TagVersionConflictResponse)},
)
async def delete_tag(
    tag_id: uuid.UUID,
    base_version: int | None = base_version_query(),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        await versions.check_tag(db, tag_id, owner_id(user), base_version)
        await tag_service.delete_tag(db, tag_id, owner_id(user))
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    except VersionConflictError as exc:
        raise version_conflict_http_error(exc, _tag_response) from exc


@router.post(
    "/api/snippets/{snippet_id}/tags", response_model=TagLinkResponse,
    responses={**tag_errors(404), **conflict_responses(SnippetVersionConflictResponse)},
)
async def add_tag(
    snippet_id: uuid.UUID,
    tag_id: uuid.UUID,
    req: TagLinkRequest | None = None,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Link; ``version`` is the snippet's afterwards. A ``base_version`` (the snippet's) that is not
    current is 409 ``version_conflict`` with the snippet."""
    try:
        await versions.check_snippet(db, snippet_id, owner_id(user), req.base_version if req else None)
        version = await tag_service.add_tag_to_snippet(db, snippet_id, tag_id, owner_id(user))
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    except VersionConflictError as exc:
        raise version_conflict_http_error(exc, _snippet_response) from exc
    return TagLinkResponse(ok=True, version=version)


@router.delete(
    "/api/snippets/{snippet_id}/tags/{tag_id}", response_model=TagLinkResponse,
    responses={**tag_errors(404), **conflict_responses(SnippetVersionConflictResponse)},
)
async def remove_tag(
    snippet_id: uuid.UUID,
    tag_id: uuid.UUID,
    base_version: int | None = base_version_query(),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Unlink; ``version`` is the snippet's afterwards (200 with a body, so the client learns it).
    A stale ``base_version`` is 409 ``version_conflict`` with the snippet."""
    try:
        await versions.check_snippet(db, snippet_id, owner_id(user), base_version)
        version = await tag_service.remove_tag_from_snippet(db, snippet_id, tag_id, owner_id(user))
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    except VersionConflictError as exc:
        raise version_conflict_http_error(exc, _snippet_response) from exc
    return TagLinkResponse(ok=True, version=version)
