"""User-wide tags and snippet links (docs/features/tag_expressions.md#http-api)."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.api.tag_errors import owner_id, tag_errors, tag_http_error
from app.db.database import get_db
from app.models.user import User
from app.schemas.tag import TagCreate, TagLinkResponse, TagResponse, TagUpdate
from app.services import tag_service
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
    )


@router.get("/api/tags", response_model=list[TagResponse])
async def list_tags(user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """The user's tags, sorted by name ignoring case."""
    return [_tag_response(t) for t in await tag_service.list_tags(db, owner_id(user))]


@router.post("/api/tags", response_model=TagResponse, status_code=201, responses=tag_errors(409, 422))
async def create_tag(req: TagCreate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    try:
        tag = await tag_service.create_tag(db, owner_id(user), tag_name(req.name), tag_color(req.color))
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    return _tag_response(tag)


@router.put("/api/tags/{tag_id}", response_model=TagResponse, responses=tag_errors(404, 409, 422))
async def update_tag(
    tag_id: uuid.UUID, req: TagUpdate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)
):
    """Rename and/or recolor. A rename rewrites the user's presets that name the tag."""
    try:
        name = tag_name(req.name) if req.name is not None else None
        color = tag_color(req.color) if req.color is not None else None
        tag = await tag_service.update_tag(db, tag_id, owner_id(user), name, color)
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    return _tag_response(tag)


@router.delete("/api/tags/{tag_id}", status_code=204, responses=tag_errors(404))
async def delete_tag(tag_id: uuid.UUID, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    try:
        await tag_service.delete_tag(db, tag_id, owner_id(user))
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc


@router.post("/api/snippets/{snippet_id}/tags", response_model=TagLinkResponse, responses=tag_errors(404))
async def add_tag(
    snippet_id: uuid.UUID, tag_id: uuid.UUID, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)
):
    try:
        await tag_service.add_tag_to_snippet(db, snippet_id, tag_id, owner_id(user))
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    return TagLinkResponse(ok=True)


@router.delete("/api/snippets/{snippet_id}/tags/{tag_id}", status_code=204, responses=tag_errors(404))
async def remove_tag(
    snippet_id: uuid.UUID, tag_id: uuid.UUID, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)
):
    try:
        await tag_service.remove_tag_from_snippet(db, snippet_id, tag_id, owner_id(user))
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
