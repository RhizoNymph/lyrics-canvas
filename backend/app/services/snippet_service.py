import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.snippet import Snippet
from app.models.canvas import Canvas


async def create_snippet(db: AsyncSession, canvas_id: str, owner_id: str, **kwargs) -> Snippet | None:
    result = await db.execute(
        select(Canvas).where(Canvas.id == uuid.UUID(canvas_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    canvas = result.scalar_one_or_none()
    if not canvas:
        return None

    snippet = Snippet(canvas_id=uuid.UUID(canvas_id), **kwargs)
    db.add(snippet)
    await db.commit()
    # Re-fetch with tags loaded so the response includes them
    result2 = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == snippet.id)
    )
    return result2.scalar_one()


async def get_snippet(db: AsyncSession, snippet_id: str) -> Snippet | None:
    result = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == uuid.UUID(snippet_id))
    )
    return result.scalar_one_or_none()


async def get_owned_snippet(db: AsyncSession, snippet_id: str, owner_id: str) -> Snippet | None:
    result = await db.execute(
        select(Snippet).join(Canvas).options(selectinload(Snippet.tags))
        .where(Snippet.id == uuid.UUID(snippet_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    return result.scalar_one_or_none()


async def update_snippet(db: AsyncSession, snippet_id: str, owner_id: str, **kwargs) -> Snippet | None:
    result = await db.execute(
        select(Snippet).join(Canvas).options(selectinload(Snippet.tags))
        .where(Snippet.id == uuid.UUID(snippet_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    snippet = result.scalar_one_or_none()
    if not snippet:
        return None
    for key, value in kwargs.items():
        if value is not None:
            setattr(snippet, key, value)
    await db.commit()
    # Re-fetch with tags loaded
    result2 = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == snippet.id)
    )
    return result2.scalar_one()


async def delete_snippet(db: AsyncSession, snippet_id: str, owner_id: str) -> bool:
    result = await db.execute(
        select(Snippet).join(Canvas)
        .where(Snippet.id == uuid.UUID(snippet_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    snippet = result.scalar_one_or_none()
    if not snippet:
        return False
    await db.delete(snippet)
    await db.commit()
    return True


async def batch_update_positions(db: AsyncSession, updates: list[dict], owner_id: str) -> int:
    count = 0
    for update in updates:
        result = await db.execute(
            select(Snippet).join(Canvas)
            .where(Snippet.id == uuid.UUID(update["id"]), Canvas.owner_id == uuid.UUID(owner_id))
        )
        snippet = result.scalar_one_or_none()
        if snippet:
            snippet.position_x = update.get("position_x", snippet.position_x)
            snippet.position_y = update.get("position_y", snippet.position_y)
            count += 1
    await db.commit()
    return count
