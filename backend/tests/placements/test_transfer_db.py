"""POST /canvases/{cid}/snippets/transfer semantics on Postgres: move / show, layout copied,
z stacking, already_on_target untouched, all-or-nothing validation (see tests/tags/db.py)."""

from __future__ import annotations

import uuid

import pytest

from app.services import group_service, snippet_service
from app.services.placements.errors import CanvasNotFoundError, PlacementNotFoundError, SameCanvasError
from app.services.placements.transfer import TransferMode, transfer
from tests.placements.db import (
    add_recording,
    make_world,
    place_new,
    placements_of,
    recordings_of,
    snippets_without_placement,
)
from tests.tags.db import requires_db

pytestmark = requires_db


async def _z(db, canvas_id, owner, snippet_id, z):
    await snippet_service.update_snippet(db, str(snippet_id), str(owner), canvas_id=canvas_id, content_edits={}, z_index=z)


async def test_show_copies_position_and_size_stacks_on_top_and_ungroups(db):
    w = await make_world(db)
    group = await group_service.create_group(db, str(w.song), str(w.owner), "Verse")
    low = await place_new(db, w.song, w.owner, "low", position_x=10.0, position_y=20.0, width=111.0, height=55.0, group_id=group.id)
    high = await place_new(db, w.song, w.owner, "high", position_x=-30.0, position_y=40.0, width=222.0, height=66.0)
    await _z(db, w.song, w.owner, low.id, 2)
    await _z(db, w.song, w.owner, high.id, 8)
    existing = await place_new(db, w.drafts, w.owner, "already there")
    await _z(db, w.drafts, w.owner, existing.id, 5)

    # request order is high, low: placed keeps request order, z follows source z order
    result = await transfer(db, w.song, w.drafts, [high.id, low.id, high.id], TransferMode.show, w.owner)
    assert result.target_canvas_id == w.drafts
    assert result.placed == (high.id, low.id)
    assert result.already_on_target == ()
    assert result.removed_from_source is False

    low_on = (await placements_of(db, low.id))
    high_on = (await placements_of(db, high.id))
    assert set(low_on) == set(high_on) == {w.song, w.drafts}  # show keeps the source placements
    t_low, t_high = low_on[w.drafts], high_on[w.drafts]
    assert (t_low.position_x, t_low.position_y, t_low.width, t_low.height) == (10.0, 20.0, 111.0, 55.0)
    assert (t_high.position_x, t_high.position_y, t_high.width, t_high.height) == (-30.0, 40.0, 222.0, 66.0)
    assert (t_low.z_index, t_high.z_index) == (6, 7)  # max z on target (5) + 1 + rank in source z order
    assert t_low.group_id is None
    assert low_on[w.song].group_id == group.id


async def test_snippets_already_on_the_target_keep_their_target_placement(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "a", position_x=1.0)
    b = await place_new(db, w.song, w.owner, "b", position_x=2.0)
    await transfer(db, w.song, w.drafts, [a.id], TransferMode.show, w.owner)
    await snippet_service.update_snippet(db, str(a.id), str(w.owner), canvas_id=w.drafts, content_edits={}, position_x=500.0, z_index=3)
    before = (await placements_of(db, a.id))[w.drafts]

    result = await transfer(db, w.song, w.drafts, [a.id, b.id], TransferMode.show, w.owner)
    assert result.placed == (b.id,)
    assert result.already_on_target == (a.id,)
    after = (await placements_of(db, a.id))[w.drafts]
    assert (after.id, after.position_x, after.z_index) == (before.id, 500.0, 3)
    assert (await placements_of(db, b.id))[w.drafts].z_index == 4


async def test_an_empty_target_starts_at_z_zero(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "a")
    await transfer(db, w.song, w.drafts, [a.id], TransferMode.show, w.owner)
    assert (await placements_of(db, a.id))[w.drafts].z_index == 0


async def test_move_removes_the_source_placements_and_keeps_recordings(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "a")
    b = await place_new(db, w.song, w.owner, "b")
    await transfer(db, w.song, w.drafts, [b.id], TransferMode.show, w.owner)
    rec = await add_recording(db, a.id)

    result = await transfer(db, w.song, w.drafts, [a.id, b.id], TransferMode.move, w.owner)
    assert result.placed == (a.id,)
    assert result.already_on_target == (b.id,)
    assert result.removed_from_source is True
    assert set(await placements_of(db, a.id)) == {w.drafts}
    assert set(await placements_of(db, b.id)) == {w.drafts}
    assert await recordings_of(db, a.id) == {rec}
    assert await snippets_without_placement(db) == 0


async def test_same_canvas_is_refused(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "a")
    with pytest.raises(SameCanvasError):
        await transfer(db, w.song, w.song, [a.id], TransferMode.move, w.owner)


@pytest.mark.parametrize("which", ["source", "target", "missing"])
async def test_canvases_must_both_be_the_callers(db, which):
    w = await make_world(db)
    a_id = (await place_new(db, w.song, w.owner, "a")).id  # rollback expires the ORM objects
    source, target = {
        "source": (w.foreign, w.drafts),
        "target": (w.song, w.foreign),
        "missing": (w.song, uuid.uuid4()),
    }[which]
    with pytest.raises(CanvasNotFoundError):
        await transfer(db, source, target, [a_id], TransferMode.move, w.owner)
    await db.rollback()
    assert set(await placements_of(db, a_id)) == {w.song}


async def test_one_snippet_not_on_the_source_fails_the_whole_transfer(db):
    w = await make_world(db)
    a_id = (await place_new(db, w.song, w.owner, "a")).id  # rollback expires the ORM objects
    stray = (await place_new(db, w.drafts, w.owner, "on drafts")).id
    other = (await place_new(db, w.foreign, w.other_user, "theirs")).id
    for bad in (stray, other, uuid.uuid4()):
        with pytest.raises(PlacementNotFoundError):
            await transfer(db, w.song, w.drafts, [a_id, bad], TransferMode.move, w.owner)
        await db.rollback()
        assert set(await placements_of(db, a_id)) == {w.song}
        assert set(await placements_of(db, stray)) == {w.drafts}
