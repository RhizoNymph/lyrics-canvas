import secrets
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.canvas import Canvas
from app.models.snippet import Snippet
from app.models.group import Group
from app.models.tag import Tag


async def create_canvas(db: AsyncSession, owner_id: str, title: str = "Untitled Canvas") -> Canvas:
    canvas = Canvas(owner_id=uuid.UUID(owner_id), title=title)
    db.add(canvas)
    await db.commit()
    await db.refresh(canvas)
    return canvas


async def list_canvases(db: AsyncSession, owner_id: str) -> list[Canvas]:
    result = await db.execute(
        select(Canvas).where(Canvas.owner_id == uuid.UUID(owner_id)).order_by(Canvas.updated_at.desc())
    )
    return list(result.scalars().all())


async def get_canvas(db: AsyncSession, canvas_id: str, owner_id: str | None = None) -> Canvas | None:
    q = select(Canvas).options(
        selectinload(Canvas.snippets).selectinload(Snippet.tags),
        selectinload(Canvas.groups).selectinload(Group.snippets),
        selectinload(Canvas.tags),
    ).where(Canvas.id == uuid.UUID(canvas_id))
    if owner_id:
        q = q.where(Canvas.owner_id == uuid.UUID(owner_id))
    result = await db.execute(q)
    return result.scalar_one_or_none()


async def update_canvas(db: AsyncSession, canvas_id: str, owner_id: str, **kwargs) -> Canvas | None:
    canvas = await get_canvas(db, canvas_id, owner_id)
    if not canvas:
        return None
    for key, value in kwargs.items():
        if value is not None:
            setattr(canvas, key, value)
    await db.commit()
    await db.refresh(canvas)
    return canvas


async def delete_canvas(db: AsyncSession, canvas_id: str, owner_id: str) -> bool:
    canvas = await get_canvas(db, canvas_id, owner_id)
    if not canvas:
        return False
    await db.delete(canvas)
    await db.commit()
    return True


async def generate_share_token(db: AsyncSession, canvas_id: str, owner_id: str) -> str | None:
    canvas = await get_canvas(db, canvas_id, owner_id)
    if not canvas:
        return None
    canvas.share_token = secrets.token_urlsafe(32)
    canvas.is_public = True
    await db.commit()
    return canvas.share_token


async def get_canvas_by_share_token(db: AsyncSession, token: str) -> Canvas | None:
    q = select(Canvas).options(
        selectinload(Canvas.snippets).selectinload(Snippet.tags),
        selectinload(Canvas.groups).selectinload(Group.snippets),
        selectinload(Canvas.tags),
    ).where(Canvas.share_token == token)
    result = await db.execute(q)
    return result.scalar_one_or_none()


async def fork_canvas(db: AsyncSession, canvas_id: str, new_owner_id: str) -> Canvas | None:
    original = await get_canvas(db, canvas_id)
    if not original or not original.is_public:
        return None
    new_canvas = Canvas(
        owner_id=uuid.UUID(new_owner_id),
        title=f"{original.title} (fork)",
    )
    db.add(new_canvas)
    await db.flush()

    for snippet in original.snippets:
        new_snippet = Snippet(
            canvas_id=new_canvas.id,
            snippet_type=snippet.snippet_type,
            grid_mode=snippet.grid_mode,
            beats_per_line=snippet.beats_per_line,
            grid_data=snippet.grid_data,
            raw_text=snippet.raw_text,
            position_x=snippet.position_x,
            position_y=snippet.position_y,
            width=snippet.width,
            height=snippet.height,
            z_index=snippet.z_index,
        )
        db.add(new_snippet)

    await db.commit()
    await db.refresh(new_canvas)
    return new_canvas
