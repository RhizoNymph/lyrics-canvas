"""The at-least-one-placement invariant on every removal path: remove from canvas, delete
everywhere, canvas delete. Real Postgres (see tests/tags/db.py)."""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import func, select

from app.models.canvas import Canvas
from app.models.group import Group
from app.services import canvas_service
from app.services.placements.errors import PlacementNotFoundError
from app.services.placements.removal import delete_everywhere, remove_from_canvas
from tests.placements.db import (
    add_recording,
    make_world,
    place_new,
    placements_of,
    recordings_of,
    show_on,
    snippet_exists,
    snippets_without_placement,
)
from tests.tags.db import requires_db

pytestmark = requires_db


async def test_removing_a_shared_snippet_keeps_it_on_its_other_canvases(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "cat")
    await show_on(db, w.song, w.drafts, w.owner, a.id)
    rec = await add_recording(db, a.id)

    assert await remove_from_canvas(db, w.song, a.id, w.owner) is False
    assert set(await placements_of(db, a.id)) == {w.drafts}
    assert await recordings_of(db, a.id) == {rec}
    assert await snippets_without_placement(db) == 0


async def test_removing_the_last_placement_deletes_the_snippet_and_its_recordings(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "cat")
    await add_recording(db, a.id)

    assert await remove_from_canvas(db, w.song, a.id, w.owner) is True
    assert not await snippet_exists(db, a.id)
    assert await recordings_of(db, a.id) == set()
    assert await snippets_without_placement(db) == 0


async def test_removing_where_it_is_not_placed_is_placement_not_found_and_changes_nothing(db):
    w = await make_world(db)
    a_id = (await place_new(db, w.song, w.owner, "cat")).id  # rollback expires the ORM objects
    with pytest.raises(PlacementNotFoundError):
        await remove_from_canvas(db, w.drafts, a_id, w.owner)
    with pytest.raises(PlacementNotFoundError):  # not the caller's
        await remove_from_canvas(db, w.song, a_id, w.other_user)
    with pytest.raises(PlacementNotFoundError):
        await remove_from_canvas(db, w.song, uuid.uuid4(), w.owner)
    await db.rollback()
    assert set(await placements_of(db, a_id)) == {w.song}


async def test_delete_everywhere_removes_every_placement_and_recording(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "cat")
    await show_on(db, w.song, w.drafts, w.owner, a.id)
    await add_recording(db, a.id)

    assert await delete_everywhere(db, a.id, w.other_user) is False
    assert await snippet_exists(db, a.id)
    assert await delete_everywhere(db, a.id, w.owner) is True
    assert not await snippet_exists(db, a.id)
    assert await placements_of(db, a.id) == {}
    assert await recordings_of(db, a.id) == set()
    assert await delete_everywhere(db, a.id, w.owner) is False


async def test_canvas_delete_keeps_shared_snippets_and_deletes_the_rest(db):
    w = await make_world(db)
    shared = await place_new(db, w.song, w.owner, "shared")
    only = await place_new(db, w.song, w.owner, "only here")
    elsewhere = await place_new(db, w.drafts, w.owner, "on drafts")
    await show_on(db, w.song, w.drafts, w.owner, shared.id)
    shared_rec, only_rec = await add_recording(db, shared.id), await add_recording(db, only.id)
    group = Group(id=uuid.uuid4(), canvas_id=w.song, name="Verse")
    db.add(group)
    await db.commit()

    assert await canvas_service.delete_canvas(db, str(w.song), str(w.other_user)) is False
    assert await canvas_service.delete_canvas(db, str(w.song), str(w.owner)) is True

    assert (await db.execute(select(func.count()).select_from(Canvas).where(Canvas.id == w.song))).scalar_one() == 0
    assert (await db.execute(select(func.count()).select_from(Group).where(Group.canvas_id == w.song))).scalar_one() == 0
    assert set(await placements_of(db, shared.id)) == {w.drafts}
    assert await recordings_of(db, shared.id) == {shared_rec}
    assert not await snippet_exists(db, only.id)
    assert await recordings_of(db, only.id) == set() and only_rec
    assert await snippet_exists(db, elsewhere.id)
    assert await snippets_without_placement(db) == 0
