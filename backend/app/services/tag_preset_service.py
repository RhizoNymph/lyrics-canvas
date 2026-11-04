"""Saved tag-expression presets, per user. Expressions arrive already normalized (tag_values)."""

from __future__ import annotations

import logging
import uuid

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.tag_preset import TagPreset
from app.services.tag_errors import PresetNameTakenError, PresetNotFoundError
from app.services.tag_values import PresetExpression, PresetName

logger = logging.getLogger(__name__)


async def _owned(db: AsyncSession, preset_id: uuid.UUID, owner_id: uuid.UUID) -> TagPreset:
    result = await db.execute(select(TagPreset).where(TagPreset.id == preset_id, TagPreset.user_id == owner_id))
    preset = result.scalar_one_or_none()
    if preset is None:
        raise PresetNotFoundError(f"preset {preset_id} not found")
    return preset


async def _name_taken(db: AsyncSession, owner_id: uuid.UUID, name: str, except_id: uuid.UUID | None = None) -> bool:
    query = select(TagPreset.id).where(TagPreset.user_id == owner_id, func.lower(TagPreset.name) == name.lower())
    if except_id is not None:
        query = query.where(TagPreset.id != except_id)
    return (await db.execute(query)).first() is not None


async def _commit(db: AsyncSession, preset: TagPreset) -> TagPreset:
    """Commit; a unique-index race on (user_id, lower(name)) becomes PresetNameTakenError."""
    name = preset.name  # read before a rollback expires it
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise PresetNameTakenError(f"a preset named {name!r} already exists") from exc
    await db.refresh(preset)
    return preset


async def list_presets(db: AsyncSession, owner_id: uuid.UUID) -> list[TagPreset]:
    result = await db.execute(
        select(TagPreset).where(TagPreset.user_id == owner_id).order_by(func.lower(TagPreset.name), TagPreset.id)
    )
    return list(result.scalars().all())


async def create_preset(db: AsyncSession, owner_id: uuid.UUID, name: PresetName, expression: PresetExpression) -> TagPreset:
    if await _name_taken(db, owner_id, name):
        raise PresetNameTakenError(f"a preset named {name!r} already exists")
    preset = TagPreset(user_id=owner_id, name=name, expression=expression)
    db.add(preset)
    preset = await _commit(db, preset)
    logger.info("tag_preset.created user_id=%s preset_id=%s", owner_id, preset.id)
    return preset


async def update_preset(
    db: AsyncSession, preset_id: uuid.UUID, owner_id: uuid.UUID, name: PresetName | None, expression: PresetExpression | None
) -> TagPreset:
    preset = await _owned(db, preset_id, owner_id)
    if name is not None and name != preset.name:
        if await _name_taken(db, owner_id, name, except_id=preset.id):
            raise PresetNameTakenError(f"a preset named {name!r} already exists")
        preset.name = name
    if expression is not None:
        preset.expression = expression
    preset.updated_at = func.now()
    preset = await _commit(db, preset)
    logger.info("tag_preset.updated user_id=%s preset_id=%s", owner_id, preset_id)
    return preset


async def delete_preset(db: AsyncSession, preset_id: uuid.UUID, owner_id: uuid.UUID) -> None:
    preset = await _owned(db, preset_id, owner_id)
    await db.delete(preset)
    await db.commit()
    logger.info("tag_preset.deleted user_id=%s preset_id=%s", owner_id, preset_id)
