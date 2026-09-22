import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.tag import Tag, snippet_tags
from app.models.canvas import Canvas
from app.models.snippet import Snippet


async def create_tag(db: AsyncSession, canvas_id: str, owner_id: str, name: str, color: str = "#6366f1") -> Tag | None:
    result = await db.execute(
        select(Canvas).where(Canvas.id == uuid.UUID(canvas_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    if not result.scalar_one_or_none():
        return None

    tag = Tag(canvas_id=uuid.UUID(canvas_id), name=name, color=color)
    db.add(tag)
    await db.commit()
    await db.refresh(tag)
    return tag


async def update_tag(db: AsyncSession, tag_id: str, owner_id: str, name: str | None = None, color: str | None = None) -> Tag | None:
    result = await db.execute(
        select(Tag).join(Canvas)
        .where(Tag.id == uuid.UUID(tag_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    tag = result.scalar_one_or_none()
    if not tag:
        return None
    if name is not None:
        tag.name = name
    if color is not None:
        tag.color = color
    await db.commit()
    await db.refresh(tag)
    return tag


async def delete_tag(db: AsyncSession, tag_id: str, owner_id: str) -> bool:
    result = await db.execute(
        select(Tag).join(Canvas)
        .where(Tag.id == uuid.UUID(tag_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    tag = result.scalar_one_or_none()
    if not tag:
        return False
    await db.delete(tag)
    await db.commit()
    return True


async def add_tag_to_snippet(db: AsyncSession, snippet_id: str, tag_id: str, owner_id: str) -> bool:
    result = await db.execute(
        select(Snippet).join(Canvas)
        .where(Snippet.id == uuid.UUID(snippet_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    snippet = result.scalar_one_or_none()
    if not snippet:
        return False

    result = await db.execute(select(Tag).where(Tag.id == uuid.UUID(tag_id)))
    tag = result.scalar_one_or_none()
    if not tag:
        return False

    await db.execute(snippet_tags.insert().values(snippet_id=uuid.UUID(snippet_id), tag_id=uuid.UUID(tag_id)))
    await db.commit()
    return True


async def remove_tag_from_snippet(db: AsyncSession, snippet_id: str, tag_id: str, owner_id: str) -> bool:
    await db.execute(
        snippet_tags.delete().where(
            snippet_tags.c.snippet_id == uuid.UUID(snippet_id),
            snippet_tags.c.tag_id == uuid.UUID(tag_id),
        )
    )
    await db.commit()
    return True
