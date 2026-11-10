"""Canvas CRUD, sharing and fork (docs/backend/canvas-crud.md).

A canvas shows the snippets placed on it (docs/features/snippet_placements.md). Deleting a canvas
removes its placements and groups, then the snippets left with no placement, in one transaction.
"""

import dataclasses
import logging
import secrets
import uuid
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.canvas import Canvas
from app.models.group import Group
from app.models.placement import SnippetPlacement
from app.models.snippet import Snippet
from app.services.canvas_title import CanvasTitle, fork_title
from app.services.placements.queries import placed_on_canvas
from app.services.placements.removal import delete_orphans, lock_snippets
from app.services.placements.view import CanvasRef, PlacedSnippet

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class CanvasDetail:
    """A canvas with the snippets placed on it and its groups (``Group.placements`` loaded)."""

    canvas: Canvas
    snippets: list[PlacedSnippet]
    groups: list[Group]

    @property
    def id(self) -> uuid.UUID:
        return self.canvas.id

    @property
    def owner_id(self) -> uuid.UUID:
        return self.canvas.owner_id

    @property
    def title(self) -> str:
        return self.canvas.title

    @property
    def is_public(self) -> bool:
        return self.canvas.is_public

    @property
    def share_token(self) -> str | None:
        return self.canvas.share_token

    @property
    def viewport_state(self) -> dict | None:
        return self.canvas.viewport_state

    @property
    def created_at(self) -> datetime:
        return self.canvas.created_at

    @property
    def updated_at(self) -> datetime:
        return self.canvas.updated_at


async def create_canvas(db: AsyncSession, owner_id: str, title: CanvasTitle) -> Canvas:
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


async def _canvas(db: AsyncSession, canvas_id: uuid.UUID, owner_id: uuid.UUID | None) -> Canvas | None:
    q = select(Canvas).where(Canvas.id == canvas_id)
    if owner_id is not None:
        q = q.where(Canvas.owner_id == owner_id)
    return (await db.execute(q)).scalar_one_or_none()


async def _detail(db: AsyncSession, canvas: Canvas) -> CanvasDetail:
    groups = await db.execute(
        select(Group).options(selectinload(Group.placements)).where(Group.canvas_id == canvas.id)
        .order_by(Group.created_at, Group.id)
        .execution_options(populate_existing=True)
    )
    return CanvasDetail(canvas=canvas, snippets=await placed_on_canvas(db, canvas.id), groups=list(groups.scalars().all()))


async def get_canvas(db: AsyncSession, canvas_id: str, owner_id: str | None = None) -> CanvasDetail | None:
    canvas = await _canvas(db, uuid.UUID(canvas_id), uuid.UUID(owner_id) if owner_id else None)
    return await _detail(db, canvas) if canvas else None


async def update_canvas(db: AsyncSession, canvas_id: str, owner_id: str, **kwargs) -> Canvas | None:
    """Set the given non-None fields; a ``title`` must already be validated (canvas_title)."""
    canvas = await _canvas(db, uuid.UUID(canvas_id), uuid.UUID(owner_id))
    if not canvas:
        return None
    for key, value in kwargs.items():
        if value is not None:
            setattr(canvas, key, value)
    await db.commit()
    await db.refresh(canvas)
    return canvas


async def delete_canvas(db: AsyncSession, canvas_id: str, owner_id: str) -> bool:
    cid = uuid.UUID(canvas_id)
    canvas = await _canvas(db, cid, uuid.UUID(owner_id))
    if not canvas:
        return False
    placed = (await db.execute(
        select(SnippetPlacement.snippet_id).where(SnippetPlacement.canvas_id == cid)
    )).scalars().all()
    await lock_snippets(db, placed)
    await db.execute(
        delete(SnippetPlacement).where(SnippetPlacement.canvas_id == cid).execution_options(synchronize_session=False)
    )
    await db.execute(delete(Group).where(Group.canvas_id == cid).execution_options(synchronize_session=False))
    await db.execute(delete(Canvas).where(Canvas.id == cid).execution_options(synchronize_session=False))
    deleted = await delete_orphans(db, placed)
    await db.commit()
    logger.info(
        "canvas.deleted canvas_id=%s placements=%d snippets_deleted=%d", canvas_id, len(placed), len(deleted)
    )
    return True


async def generate_share_token(db: AsyncSession, canvas_id: str, owner_id: str) -> str | None:
    canvas = await _canvas(db, uuid.UUID(canvas_id), uuid.UUID(owner_id))
    if not canvas:
        return None
    canvas.share_token = secrets.token_urlsafe(32)
    canvas.is_public = True
    await db.commit()
    return canvas.share_token


async def get_canvas_by_share_token(db: AsyncSession, token: str) -> CanvasDetail | None:
    """The shared view. Each snippet's ``canvases`` lists only the shared canvas: the owner's
    other canvas titles are not shown to whoever holds the link."""
    canvas = (await db.execute(select(Canvas).where(Canvas.share_token == token))).scalar_one_or_none()
    if not canvas:
        return None
    detail = await _detail(db, canvas)
    only_this = (CanvasRef(id=canvas.id, title=canvas.title),)
    return dataclasses.replace(detail, snippets=[dataclasses.replace(s, canvases=only_this) for s in detail.snippets])


def fork_copy(placed: PlacedSnippet, new_owner_id: uuid.UUID, canvas_id: uuid.UUID) -> tuple[Snippet, SnippetPlacement]:
    """A new snippet with ``placed``'s content, owned by the forking user, and its one placement
    on the new canvas at the same layout. Tags, recordings and groups are not copied."""
    snippet = Snippet(
        id=uuid.uuid4(),
        owner_id=new_owner_id,
        snippet_type=placed.snippet_type,
        grid_mode=placed.grid_mode,
        beats_per_line=placed.beats_per_line,
        grid_data=placed.grid_data,
        raw_text=placed.raw_text,
        phonetics=placed.phonetics,
        derived_from=placed.derived_from,
        # a fork copies the words, it does not edit them
        content_updated_at=placed.content_updated_at,
    )
    placement = SnippetPlacement(
        snippet_id=snippet.id,
        canvas_id=canvas_id,
        position_x=placed.position_x,
        position_y=placed.position_y,
        width=placed.width,
        height=placed.height,
        z_index=placed.z_index,
    )
    return snippet, placement


async def fork_canvas(db: AsyncSession, canvas_id: str, new_owner_id: str) -> Canvas | None:
    original = await _canvas(db, uuid.UUID(canvas_id), None)
    if not original or not original.is_public:
        return None
    owner = uuid.UUID(new_owner_id)
    placed = await placed_on_canvas(db, original.id)
    new_canvas = Canvas(id=uuid.uuid4(), owner_id=owner, title=fork_title(original.title))
    db.add(new_canvas)
    for source in placed:
        snippet, placement = fork_copy(source, owner, new_canvas.id)
        db.add(snippet)
        db.add(placement)
    await db.commit()
    await db.refresh(new_canvas)
    logger.info("canvas.forked source=%s canvas_id=%s snippets=%d", canvas_id, new_canvas.id, len(placed))
    return new_canvas
