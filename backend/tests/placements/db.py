"""Helpers for the placement DB tests: a small world of users, canvases and placed snippets built
through the services (the ``migrated`` and ``db`` fixtures are in conftest.py)."""

from __future__ import annotations

import uuid
from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement
from app.models.recording import SnippetRecording, SplitMode
from app.models.snippet import Snippet
from app.models.user import User
from app.services import snippet_service
from app.services.placements.transfer import TransferMode, transfer
from app.services.placements.view import PlacedSnippet
from app.services.snippet_content import derive_new_content


async def fresh(db: AsyncSession) -> AsyncSession:
    """A second session on the same database, to read what was committed."""
    return async_sessionmaker(db.bind, class_=AsyncSession, expire_on_commit=False)()


@dataclass
class World:
    owner: uuid.UUID
    other_user: uuid.UUID
    song: uuid.UUID  # canvas "Song"
    drafts: uuid.UUID  # canvas "drafts"
    foreign: uuid.UUID  # the other user's canvas


async def make_user(db: AsyncSession) -> uuid.UUID:
    user = User(id=uuid.uuid4(), email=f"{uuid.uuid4()}@example.test", display_name="t")
    db.add(user)
    await db.flush()
    return user.id


async def make_canvas(db: AsyncSession, owner: uuid.UUID, title: str) -> uuid.UUID:
    canvas = Canvas(id=uuid.uuid4(), owner_id=owner, title=title)
    db.add(canvas)
    await db.flush()
    return canvas.id


async def make_world(db: AsyncSession) -> World:
    owner, other = await make_user(db), await make_user(db)
    world = World(
        owner=owner,
        other_user=other,
        song=await make_canvas(db, owner, "Song"),
        drafts=await make_canvas(db, owner, "drafts"),
        foreign=await make_canvas(db, other, "Theirs"),
    )
    await db.commit()
    return world


async def place_new(db: AsyncSession, canvas_id: uuid.UUID, owner: uuid.UUID, text: str, **layout) -> PlacedSnippet:
    content = derive_new_content(text)
    fields = dict(position_x=0.0, position_y=0.0, width=content.width, height=content.height, group_id=None)
    fields.update(layout)
    placed = await snippet_service.create_snippet(
        db, str(canvas_id), str(owner), raw_text=content.raw_text, grid_data=content.grid_data, phonetics=content.phonetics, **fields
    )
    assert placed is not None
    return placed


async def show_on(db: AsyncSession, source: uuid.UUID, target: uuid.UUID, owner: uuid.UUID, *ids: uuid.UUID) -> None:
    await transfer(db, source, target, list(ids), TransferMode.show, owner)


async def add_recording(db: AsyncSession, snippet_id: uuid.UUID) -> uuid.UUID:
    row = SnippetRecording(
        id=uuid.uuid4(), snippet_id=snippet_id, mime_type="audio/ogg", duration_ms=10, byte_size=1, audio=b"\x00",
        transcript="a", split_mode=SplitMode.taps,
    )
    db.add(row)
    await db.commit()
    return row.id


async def placements_of(db: AsyncSession, snippet_id: uuid.UUID) -> dict[uuid.UUID, SnippetPlacement]:
    result = await db.execute(
        select(SnippetPlacement).where(SnippetPlacement.snippet_id == snippet_id).execution_options(populate_existing=True)
    )
    return {p.canvas_id: p for p in result.scalars().all()}


async def snippet_exists(db: AsyncSession, snippet_id: uuid.UUID) -> bool:
    return (await db.execute(select(func.count()).select_from(Snippet).where(Snippet.id == snippet_id))).scalar_one() == 1


async def recordings_of(db: AsyncSession, snippet_id: uuid.UUID) -> set[uuid.UUID]:
    result = await db.execute(select(SnippetRecording.id).where(SnippetRecording.snippet_id == snippet_id))
    return set(result.scalars().all())


async def snippets_without_placement(db: AsyncSession) -> int:
    """Must always be 0: the at-least-one-placement invariant."""
    result = await db.execute(
        select(func.count()).select_from(Snippet)
        .where(~select(SnippetPlacement.id).where(SnippetPlacement.snippet_id == Snippet.id).exists())
    )
    return result.scalar_one()
