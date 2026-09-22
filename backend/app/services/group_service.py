import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.group import Group
from app.models.canvas import Canvas
from app.models.snippet import Snippet


async def create_group(db: AsyncSession, canvas_id: str, owner_id: str, name: str, **kwargs) -> Group | None:
    result = await db.execute(
        select(Canvas).where(Canvas.id == uuid.UUID(canvas_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    if not result.scalar_one_or_none():
        return None

    group = Group(canvas_id=uuid.UUID(canvas_id), name=name, **kwargs)
    db.add(group)
    await db.commit()
    await db.refresh(group)
    return group


async def get_group(db: AsyncSession, group_id: str) -> Group | None:
    result = await db.execute(
        select(Group).options(selectinload(Group.snippets)).where(Group.id == uuid.UUID(group_id))
    )
    return result.scalar_one_or_none()


async def update_group(db: AsyncSession, group_id: str, owner_id: str, **kwargs) -> Group | None:
    result = await db.execute(
        select(Group).join(Canvas).options(selectinload(Group.snippets))
        .where(Group.id == uuid.UUID(group_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    group = result.scalar_one_or_none()
    if not group:
        return None
    for key, value in kwargs.items():
        if value is not None:
            setattr(group, key, value)
    await db.commit()
    await db.refresh(group)
    return group


async def delete_group(db: AsyncSession, group_id: str, owner_id: str) -> bool:
    result = await db.execute(
        select(Group).join(Canvas)
        .where(Group.id == uuid.UUID(group_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    group = result.scalar_one_or_none()
    if not group:
        return False
    # Unassign snippets from this group
    for snippet in group.snippets:
        snippet.group_id = None
    await db.delete(group)
    await db.commit()
    return True
