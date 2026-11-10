"""Groups of one canvas. Members are placements of that canvas (``SnippetPlacement.group_id``);
a snippet on several canvases can be in a different group on each."""

import uuid

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.group import Group
from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement


async def create_group(db: AsyncSession, canvas_id: str, owner_id: str, name: str, **kwargs) -> Group | None:
    result = await db.execute(
        select(Canvas).where(Canvas.id == uuid.UUID(canvas_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    if not result.scalar_one_or_none():
        return None

    group = Group(id=uuid.uuid4(), canvas_id=uuid.UUID(canvas_id), name=name, **kwargs)
    db.add(group)
    await db.commit()
    return await get_group(db, str(group.id))


async def get_group(db: AsyncSession, group_id: str) -> Group | None:
    result = await db.execute(
        select(Group).options(selectinload(Group.placements)).where(Group.id == uuid.UUID(group_id))
        .execution_options(populate_existing=True)
    )
    return result.scalar_one_or_none()


async def update_group(db: AsyncSession, group_id: str, owner_id: str, **kwargs) -> Group | None:
    result = await db.execute(
        select(Group).join(Canvas).options(selectinload(Group.placements))
        .where(Group.id == uuid.UUID(group_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    group = result.scalar_one_or_none()
    if not group:
        return None
    for key, value in kwargs.items():
        if value is not None:
            setattr(group, key, value)
    await db.commit()
    return await get_group(db, group_id)


async def delete_group(db: AsyncSession, group_id: str, owner_id: str) -> bool:
    result = await db.execute(
        select(Group).join(Canvas)
        .where(Group.id == uuid.UUID(group_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    group = result.scalar_one_or_none()
    if not group:
        return False
    # Ungroup the member placements (the FK would SET NULL too; explicit keeps the session honest)
    await db.execute(
        update(SnippetPlacement).where(SnippetPlacement.group_id == group.id).values(group_id=None)
        .execution_options(synchronize_session=False)
    )
    await db.delete(group)
    await db.commit()
    return True
