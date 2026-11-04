"""User-wide tags: CRUD, snippet links, and preset rewrites on rename.

Every operation is scoped to ``owner_id``: a tag, snippet or preset of another user is reported as
not found. Failures are the typed errors of app/services/tag_errors.py.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.canvas import Canvas
from app.models.snippet import Snippet
from app.models.tag import Tag, snippet_tags
from app.models.tag_preset import TagPreset
from app.services.tag_errors import (
    PresetExpressionTooLongError,
    SnippetNotFoundError,
    TagNameTakenError,
    TagNotFoundError,
)
from app.services.tag_expr import MAX_LENGTH, TagExpressionError, rename_tag
from app.services.tag_values import TagColor, TagName

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class TagSummary:
    """A tag with the number of snippets carrying it (the TagResponse shape)."""

    id: uuid.UUID
    name: str
    color: str
    snippet_count: int
    created_at: datetime


def _count():
    return func.count(snippet_tags.c.snippet_id)


async def _summary(db: AsyncSession, tag_id: uuid.UUID) -> TagSummary:
    result = await db.execute(
        select(Tag, _count()).outerjoin(snippet_tags, snippet_tags.c.tag_id == Tag.id).where(Tag.id == tag_id).group_by(Tag.id)
    )
    tag, count = result.one()
    return TagSummary(tag.id, tag.name, tag.color, count, tag.created_at)


async def _owned_tag(db: AsyncSession, tag_id: uuid.UUID, owner_id: uuid.UUID) -> Tag:
    result = await db.execute(select(Tag).where(Tag.id == tag_id, Tag.user_id == owner_id))
    tag = result.scalar_one_or_none()
    if tag is None:
        raise TagNotFoundError(f"tag {tag_id} not found")
    return tag


async def _owned_snippet_exists(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID) -> None:
    result = await db.execute(
        select(Snippet.id).join(Canvas).where(Snippet.id == snippet_id, Canvas.owner_id == owner_id)
    )
    if result.scalar_one_or_none() is None:
        raise SnippetNotFoundError(f"snippet {snippet_id} not found")


async def _name_taken(db: AsyncSession, owner_id: uuid.UUID, name: str, except_id: uuid.UUID | None = None) -> bool:
    query = select(Tag.id).where(Tag.user_id == owner_id, func.lower(Tag.name) == name.lower())
    if except_id is not None:
        query = query.where(Tag.id != except_id)
    return (await db.execute(query)).first() is not None


async def _commit_or_name_taken(db: AsyncSession, name: str) -> None:
    """Commit; a unique-index race on (user_id, lower(name)) becomes TagNameTakenError."""
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise TagNameTakenError(f"a tag named {name!r} already exists") from exc


async def list_tags(db: AsyncSession, owner_id: uuid.UUID) -> list[TagSummary]:
    result = await db.execute(
        select(Tag, _count())
        .outerjoin(snippet_tags, snippet_tags.c.tag_id == Tag.id)
        .where(Tag.user_id == owner_id)
        .group_by(Tag.id)
        .order_by(func.lower(Tag.name), Tag.id)
    )
    return [TagSummary(tag.id, tag.name, tag.color, count, tag.created_at) for tag, count in result.all()]


async def create_tag(db: AsyncSession, owner_id: uuid.UUID, name: TagName, color: TagColor) -> TagSummary:
    if await _name_taken(db, owner_id, name):
        raise TagNameTakenError(f"a tag named {name!r} already exists")
    tag = Tag(user_id=owner_id, name=name, color=color)
    db.add(tag)
    await _commit_or_name_taken(db, name)
    logger.info("tag.created user_id=%s tag_id=%s", owner_id, tag.id)
    return await _summary(db, tag.id)


async def update_tag(
    db: AsyncSession, tag_id: uuid.UUID, owner_id: uuid.UUID, name: TagName | None, color: TagColor | None
) -> TagSummary:
    """Change name and/or color. A rename rewrites every preset of the user that names the tag,
    in the same transaction; if any rewritten preset would exceed the length limit, nothing changes."""
    tag = await _owned_tag(db, tag_id, owner_id)
    rewritten = 0
    renamed = name is not None and name != tag.name
    if name is not None and renamed:
        if await _name_taken(db, owner_id, name, except_id=tag.id):
            raise TagNameTakenError(f"a tag named {name!r} already exists")
        rewritten = await _rewrite_presets(db, owner_id, tag.name, name)
        tag.name = name
    if color is not None:
        tag.color = color
    await _commit_or_name_taken(db, tag.name)
    logger.info("tag.updated user_id=%s tag_id=%s renamed=%s presets_rewritten=%d", owner_id, tag_id, renamed, rewritten)
    return await _summary(db, tag_id)


async def _rewrite_presets(db: AsyncSession, owner_id: uuid.UUID, old: str, new: str) -> int:
    result = await db.execute(select(TagPreset).where(TagPreset.user_id == owner_id).with_for_update())
    changed = 0
    for preset in result.scalars().all():
        try:
            expression = rename_tag(preset.expression, old, new)
        except TagExpressionError as exc:
            # stored expressions are normalized on write, so this means a hand-edited row
            logger.warning("tag.rename.preset_unparseable preset_id=%s code=%s position=%d", preset.id, exc.code.value, exc.position)
            continue
        if expression == preset.expression:
            continue
        if len(expression) > MAX_LENGTH:
            message = f"renaming the tag would make preset {preset.name!r} longer than {MAX_LENGTH} characters"
            await db.rollback()
            raise PresetExpressionTooLongError(message)
        preset.expression = expression
        changed += 1
    return changed


async def delete_tag(db: AsyncSession, tag_id: uuid.UUID, owner_id: uuid.UUID) -> None:
    """Delete the tag and its snippet links. Presets keep the name (it now matches nothing)."""
    tag = await _owned_tag(db, tag_id, owner_id)
    await db.delete(tag)
    await db.commit()
    logger.info("tag.deleted user_id=%s tag_id=%s", owner_id, tag_id)


async def add_tag_to_snippet(db: AsyncSession, snippet_id: uuid.UUID, tag_id: uuid.UUID, owner_id: uuid.UUID) -> None:
    """Link a tag to a snippet; both must belong to the user. Linking twice is a no-op."""
    await _owned_snippet_exists(db, snippet_id, owner_id)
    await _owned_tag(db, tag_id, owner_id)
    await db.execute(insert(snippet_tags).values(snippet_id=snippet_id, tag_id=tag_id).on_conflict_do_nothing())
    await db.commit()


async def remove_tag_from_snippet(db: AsyncSession, snippet_id: uuid.UUID, tag_id: uuid.UUID, owner_id: uuid.UUID) -> None:
    """Unlink; both must belong to the user. Unlinking a tag the snippet does not carry is a no-op."""
    await _owned_snippet_exists(db, snippet_id, owner_id)
    await _owned_tag(db, tag_id, owner_id)
    await db.execute(
        snippet_tags.delete().where(snippet_tags.c.snippet_id == snippet_id, snippet_tags.c.tag_id == tag_id)
    )
    await db.commit()
