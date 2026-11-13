"""User-wide tags: CRUD, snippet links, and preset rewrites on rename.

Every operation is scoped to ``owner_id``: a tag, snippet or preset of another user is reported as
not found. Failures are the typed errors of app/services/tag_errors.py.

Content versions (docs/features/sync_versions.md#versions): a name or color change bumps the tag;
a link or unlink that changes a snippet's tags bumps the snippet. Deleting or renaming a tag bumps
no snippet or word.
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import exists, func, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.snippet import Snippet
from app.models.tag import Tag, snippet_tags
from app.models.tag_preset import TagPreset
from app.models.word_bank import bank_word_tags
from app.services.tag_errors import (
    PresetExpressionTooLongError,
    SnippetNotFoundError,
    TagNameTakenError,
    TagNotFoundError,
)
from app.services.tag_expr import MAX_LENGTH, TagExpressionError, rename_tag
from app.services.sync.bump import bump
from app.services.tag_values import TagColor, TagName

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class TagSummary:
    """A tag with the number of snippets and of word bank words carrying it (the TagResponse shape)."""

    id: uuid.UUID
    name: str
    color: str
    snippet_count: int
    created_at: datetime
    word_count: int = 0
    version: int = 1


def _counted():
    """``(Tag, snippet_count, word_count)`` rows, counted by correlated subqueries."""
    snippets = select(func.count()).where(snippet_tags.c.tag_id == Tag.id).correlate(Tag).scalar_subquery()
    words = select(func.count()).where(bank_word_tags.c.tag_id == Tag.id).correlate(Tag).scalar_subquery()
    return select(Tag, snippets, words)


def _to_summary(tag: Tag, snippet_count: int, word_count: int) -> TagSummary:
    return TagSummary(tag.id, tag.name, tag.color, snippet_count, tag.created_at, word_count, tag.version)


async def summary(db: AsyncSession, tag_id: uuid.UUID) -> TagSummary:
    """The tag with its counts, read fresh (the tag must exist)."""
    result = await db.execute(_counted().where(Tag.id == tag_id).execution_options(populate_existing=True))
    return _to_summary(*result.one())


async def _owned_tag(db: AsyncSession, tag_id: uuid.UUID, owner_id: uuid.UUID) -> Tag:
    result = await db.execute(select(Tag).where(Tag.id == tag_id, Tag.user_id == owner_id))
    tag = result.scalar_one_or_none()
    if tag is None:
        raise TagNotFoundError(f"tag {tag_id} not found")
    return tag


async def _locked_snippet_version(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID) -> int:
    """The snippet's version, its row locked until commit so concurrent links serialize; raises
    SnippetNotFoundError when it is not the user's."""
    result = await db.execute(
        select(Snippet.version).where(Snippet.id == snippet_id, Snippet.owner_id == owner_id).with_for_update()
    )
    version = result.scalar_one_or_none()
    if version is None:
        raise SnippetNotFoundError(f"snippet {snippet_id} not found")
    return version


def _link_exists(snippet_id: uuid.UUID, tag_id: uuid.UUID):
    return exists().where(snippet_tags.c.snippet_id == snippet_id, snippet_tags.c.tag_id == tag_id)


async def _bump_if(db: AsyncSession, snippet_id: uuid.UUID, condition) -> int | None:
    """``version + 1`` when ``condition`` holds (the link is about to change); the new version, or
    None when nothing changed. ``updated_at`` is kept: tag links never touched it."""
    result = await db.execute(
        update(Snippet)
        .where(Snippet.id == snippet_id, condition)
        .values(version=Snippet.version + 1, updated_at=Snippet.updated_at)
        .returning(Snippet.version)
        .execution_options(synchronize_session=False)
    )
    return result.scalar_one_or_none()


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
    result = await db.execute(_counted().where(Tag.user_id == owner_id).order_by(func.lower(Tag.name), Tag.id))
    return [_to_summary(*row) for row in result.all()]


async def create_tag(
    db: AsyncSession, owner_id: uuid.UUID, name: TagName, color: TagColor, *, tag_id: uuid.UUID | None = None
) -> TagSummary:
    """A new tag; ``tag_id`` is a client-chosen id (app/services/sync/client_ids.py resolves retries)."""
    if await _name_taken(db, owner_id, name):
        raise TagNameTakenError(f"a tag named {name!r} already exists")
    tag = Tag(id=tag_id or uuid.uuid4(), user_id=owner_id, name=name, color=color)
    db.add(tag)
    await _commit_or_name_taken(db, name)
    logger.info("tag.created user_id=%s tag_id=%s", owner_id, tag.id)
    return await summary(db, tag.id)


async def update_tag(
    db: AsyncSession, tag_id: uuid.UUID, owner_id: uuid.UUID, name: TagName | None, color: TagColor | None
) -> TagSummary:
    """Change name and/or color. A rename rewrites every preset of the user that names the tag,
    in the same transaction; if any rewritten preset would exceed the length limit, nothing changes.
    A change of name or color bumps the tag's version once."""
    tag = await _owned_tag(db, tag_id, owner_id)
    rewritten = 0
    renamed = name is not None and name != tag.name
    recolored = color is not None and color != tag.color
    if name is not None and renamed:
        if await _name_taken(db, owner_id, name, except_id=tag.id):
            raise TagNameTakenError(f"a tag named {name!r} already exists")
        rewritten = await _rewrite_presets(db, owner_id, tag.name, name)
        tag.name = name
    if color is not None and recolored:
        tag.color = color
    stored_name = tag.name
    if renamed or recolored:
        bump(tag)
    await _commit_or_name_taken(db, stored_name)
    logger.info(
        "tag.updated user_id=%s tag_id=%s renamed=%s recolored=%s presets_rewritten=%d",
        owner_id, tag_id, renamed, recolored, rewritten,
    )
    return await summary(db, tag_id)


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
    """Delete the tag and its snippet and word bank links (FK cascade). Presets keep the name (it
    now matches nothing)."""
    tag = await _owned_tag(db, tag_id, owner_id)
    await db.delete(tag)
    await db.commit()
    logger.info("tag.deleted user_id=%s tag_id=%s", owner_id, tag_id)


async def add_tag_to_snippet(db: AsyncSession, snippet_id: uuid.UUID, tag_id: uuid.UUID, owner_id: uuid.UUID) -> int:
    """Link a tag to a snippet; both must belong to the user. Linking twice is a no-op. A new link
    bumps the snippet's version; returns the snippet's version after the commit."""
    current = await _locked_snippet_version(db, snippet_id, owner_id)
    await _owned_tag(db, tag_id, owner_id)
    bumped = await _bump_if(db, snippet_id, ~_link_exists(snippet_id, tag_id))
    await db.execute(insert(snippet_tags).values(snippet_id=snippet_id, tag_id=tag_id).on_conflict_do_nothing())
    await db.commit()
    logger.debug("tag.linked snippet_id=%s tag_id=%s changed=%s", snippet_id, tag_id, bumped is not None)
    return current if bumped is None else bumped


async def remove_tag_from_snippet(db: AsyncSession, snippet_id: uuid.UUID, tag_id: uuid.UUID, owner_id: uuid.UUID) -> int:
    """Unlink; both must belong to the user. Unlinking a tag the snippet does not carry is a no-op.
    A removed link bumps the snippet's version; returns the snippet's version after the commit."""
    current = await _locked_snippet_version(db, snippet_id, owner_id)
    await _owned_tag(db, tag_id, owner_id)
    bumped = await _bump_if(db, snippet_id, _link_exists(snippet_id, tag_id))
    await db.execute(
        snippet_tags.delete().where(snippet_tags.c.snippet_id == snippet_id, snippet_tags.c.tag_id == tag_id)
    )
    await db.commit()
    logger.debug("tag.unlinked snippet_id=%s tag_id=%s changed=%s", snippet_id, tag_id, bumped is not None)
    return current if bumped is None else bumped
