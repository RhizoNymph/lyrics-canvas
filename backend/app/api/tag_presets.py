"""Saved tag-expression presets (docs/features/tag_expressions.md#http-api)."""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.api.tag_errors import owner_id, tag_errors, tag_http_error
from app.db.database import get_db
from app.models.tag_preset import TagPreset
from app.models.user import User
from app.schemas.tag import TagPresetCreate, TagPresetResponse, TagPresetUpdate
from app.services import tag_preset_service
from app.services.tag_errors import TagServiceError
from app.services.tag_values import preset_expression, preset_name

router = APIRouter(prefix="/api/tag-presets", tags=["tags"])


def _preset_response(p: TagPreset) -> TagPresetResponse:
    return TagPresetResponse(
        id=str(p.id), name=p.name, expression=p.expression, created_at=str(p.created_at), updated_at=str(p.updated_at)
    )


@router.get("", response_model=list[TagPresetResponse])
async def list_presets(user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """The user's presets, sorted by name ignoring case."""
    return [_preset_response(p) for p in await tag_preset_service.list_presets(db, owner_id(user))]


@router.post("", response_model=TagPresetResponse, status_code=201, responses=tag_errors(409, 422))
async def create_preset(req: TagPresetCreate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """The expression is parsed and stored normalized; empty expressions are refused."""
    try:
        preset = await tag_preset_service.create_preset(
            db, owner_id(user), preset_name(req.name), preset_expression(req.expression)
        )
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    return _preset_response(preset)


@router.put("/{preset_id}", response_model=TagPresetResponse, responses=tag_errors(404, 409, 422))
async def update_preset(
    preset_id: uuid.UUID, req: TagPresetUpdate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)
):
    try:
        name = preset_name(req.name) if req.name is not None else None
        expression = preset_expression(req.expression) if req.expression is not None else None
        preset = await tag_preset_service.update_preset(db, preset_id, owner_id(user), name, expression)
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
    return _preset_response(preset)


@router.delete("/{preset_id}", status_code=204, responses=tag_errors(404))
async def delete_preset(preset_id: uuid.UUID, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    try:
        await tag_preset_service.delete_preset(db, preset_id, owner_id(user))
    except TagServiceError as exc:
        raise tag_http_error(exc) from exc
