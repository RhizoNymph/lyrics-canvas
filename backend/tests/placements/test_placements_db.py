"""Create, read and update through placements: content shared, layout per canvas, groups per
canvas, canvases lists. Real Postgres (see tests/tags/db.py)."""

from __future__ import annotations

import uuid

import pytest

from app.models.group import Group
from app.services import canvas_service, group_service, snippet_service
from app.services.placements.errors import GroupNotFoundError, PlacementNotFoundError
from app.services.placements.view import CanvasRef
from tests.placements.db import fresh, make_canvas, make_world, place_new, placements_of, show_on
from tests.tags.db import requires_db

pytestmark = requires_db


async def test_create_places_the_new_snippet_on_the_canvas_and_owns_it(db):
    w = await make_world(db)
    placed = await place_new(db, w.song, w.owner, "cat", position_x=12.5, position_y=-3.0)
    assert placed.owner_id == w.owner
    assert placed.canvas_id == w.song
    assert (placed.position_x, placed.position_y, placed.z_index, placed.group_id) == (12.5, -3.0, 0, None)
    assert placed.canvases == (CanvasRef(w.song, "Song"),)
    assert set(await placements_of(db, placed.id)) == {w.song}


async def test_create_on_a_foreign_canvas_is_none(db):
    w = await make_world(db)
    assert await snippet_service.create_snippet(db, str(w.foreign), str(w.owner), raw_text="x") is None


async def test_create_with_a_group_of_another_canvas_is_refused(db):
    w = await make_world(db)
    other_group = await group_service.create_group(db, str(w.drafts), str(w.owner), "G")
    with pytest.raises(GroupNotFoundError):
        await place_new(db, w.song, w.owner, "cat", group_id=other_group.id)
    own_group = await group_service.create_group(db, str(w.song), str(w.owner), "G")
    placed = await place_new(db, w.song, w.owner, "cat", group_id=own_group.id)
    assert placed.group_id == own_group.id


async def test_canvas_detail_shows_each_canvas_its_own_layout_and_lists_every_canvas(db):
    w = await make_world(db)
    alpha = await make_canvas(db, w.owner, "alpha")
    await db.commit()
    a = await place_new(db, w.song, w.owner, "shared", position_x=10.0, position_y=20.0)
    b = await place_new(db, w.song, w.owner, "only here")
    await show_on(db, w.song, w.drafts, w.owner, a.id)
    await show_on(db, w.song, alpha, w.owner, a.id)
    await snippet_service.update_snippet(
        db, str(a.id), str(w.owner), canvas_id=w.drafts, content_edits={}, position_x=500.0, z_index=9
    )

    song = await canvas_service.get_canvas(db, str(w.song), str(w.owner))
    drafts = await canvas_service.get_canvas(db, str(w.drafts), str(w.owner))
    by_id = {s.id: s for s in song.snippets}
    assert set(by_id) == {a.id, b.id}
    assert [s.id for s in drafts.snippets] == [a.id]
    assert (by_id[a.id].position_x, by_id[a.id].z_index) == (10.0, 0)
    assert (drafts.snippets[0].position_x, drafts.snippets[0].position_y, drafts.snippets[0].z_index) == (500.0, 20.0, 9)
    assert by_id[a.id].placement_id != drafts.snippets[0].placement_id
    # every canvas holding it, by lower-cased title: "alpha" < "drafts" < "Song"
    expected = (CanvasRef(alpha, "alpha"), CanvasRef(w.drafts, "drafts"), CanvasRef(w.song, "Song"))
    assert by_id[a.id].canvases == expected == drafts.snippets[0].canvases
    assert by_id[b.id].canvases == (CanvasRef(w.song, "Song"),)
    assert await canvas_service.get_canvas(db, str(w.song), str(w.other_user)) is None


async def test_canvases_with_equal_titles_sort_by_id(db):
    w = await make_world(db)
    twins = [await make_canvas(db, w.owner, "Twin"), await make_canvas(db, w.owner, "twin")]
    await db.commit()
    a = await place_new(db, w.song, w.owner, "x")
    for c in twins:
        await show_on(db, w.song, c, w.owner, a.id)
    detail = await canvas_service.get_canvas(db, str(w.song), str(w.owner))
    refs = detail.snippets[0].canvases
    # "song" < "twin" == "twin"; the twins by id
    assert refs[0] == CanvasRef(w.song, "Song")
    assert [r.id for r in refs[1:]] == sorted(twins, key=str)


async def test_content_edits_are_shared_and_layout_edits_stay_on_their_canvas(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "cat", position_x=1.0, width=100.0)
    await show_on(db, w.song, w.drafts, w.owner, a.id)
    updated = await snippet_service.update_snippet(
        db, str(a.id), str(w.owner), canvas_id=w.drafts, content_edits={"raw_text": "dog"}, raw_text="dog",
        position_x=77.0, width=640.0, height=50.0,
    )
    assert updated.canvas_id == w.drafts
    assert (updated.raw_text, updated.position_x, updated.width, updated.height) == ("dog", 77.0, 640.0, 50.0)
    reader = await fresh(db)
    try:
        song = await canvas_service.get_canvas(reader, str(w.song), str(w.owner))
        (on_song,) = song.snippets
        assert on_song.raw_text == "dog"  # content is shared
        assert (on_song.position_x, on_song.width) == (1.0, 100.0)  # layout is not
    finally:
        await reader.close()


async def test_update_needs_a_placement_on_that_canvas(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "cat")
    with pytest.raises(PlacementNotFoundError):
        await snippet_service.update_snippet(db, str(a.id), str(w.owner), canvas_id=w.drafts, content_edits={}, position_x=1.0)
    with pytest.raises(PlacementNotFoundError):  # someone else's snippet
        await snippet_service.update_snippet(db, str(a.id), str(w.other_user), canvas_id=w.song, content_edits={}, position_x=1.0)


async def test_update_group_must_be_of_the_placements_canvas(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "cat")
    await show_on(db, w.song, w.drafts, w.owner, a.id)
    song_group = await group_service.create_group(db, str(w.song), str(w.owner), "Verse")
    with pytest.raises(GroupNotFoundError):
        await snippet_service.update_snippet(db, str(a.id), str(w.owner), canvas_id=w.drafts, content_edits={}, group_id=song_group.id)
    on_song = await snippet_service.update_snippet(db, str(a.id), str(w.owner), canvas_id=w.song, content_edits={}, group_id=song_group.id)
    assert on_song.group_id == song_group.id
    placements = await placements_of(db, a.id)
    assert placements[w.drafts].group_id is None


async def test_batch_positions_move_only_that_canvas(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "cat", position_x=1.0, position_y=2.0)
    b = await place_new(db, w.drafts, w.owner, "dog")
    await show_on(db, w.song, w.drafts, w.owner, a.id)
    count = await snippet_service.batch_update_positions(
        db, w.drafts,
        [{"id": a.id, "position_x": 30.0, "position_y": 40.0}, {"id": b.id, "position_x": 5.0, "position_y": None}, {"id": uuid.uuid4(), "position_x": 1.0}],
        str(w.owner),
    )
    assert count == 2
    placements = await placements_of(db, a.id)
    assert (placements[w.drafts].position_x, placements[w.drafts].position_y) == (30.0, 40.0)
    assert (placements[w.song].position_x, placements[w.song].position_y) == (1.0, 2.0)
    assert (await placements_of(db, b.id))[w.drafts].position_x == 5.0
    # another user's batch on this canvas updates nothing
    assert await snippet_service.batch_update_positions(db, w.drafts, [{"id": a.id, "position_x": 0.0}], str(w.other_user)) == 0


async def test_group_members_are_placements_of_its_canvas(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "cat")
    await show_on(db, w.song, w.drafts, w.owner, a.id)
    song_group = await group_service.create_group(db, str(w.song), str(w.owner), "Verse")
    drafts_group = await group_service.create_group(db, str(w.drafts), str(w.owner), "Draft")
    await snippet_service.update_snippet(db, str(a.id), str(w.owner), canvas_id=w.song, content_edits={}, group_id=song_group.id)
    await snippet_service.update_snippet(db, str(a.id), str(w.owner), canvas_id=w.drafts, content_edits={}, group_id=drafts_group.id)

    song = await canvas_service.get_canvas(db, str(w.song), str(w.owner))
    (group,) = song.groups
    assert [p.snippet_id for p in group.placements] == [a.id]
    assert song.snippets[0].group_id == song_group.id

    assert await group_service.delete_group(db, str(song_group.id), str(w.owner))
    placements = await placements_of(db, a.id)
    assert placements[w.song].group_id is None
    assert placements[w.drafts].group_id == drafts_group.id
    assert (await db.get(Group, drafts_group.id)) is not None


async def test_owned_snippet_lookup_goes_through_owner_id(db):
    w = await make_world(db)
    a = await place_new(db, w.song, w.owner, "cat")
    assert (await snippet_service.get_owned_snippet(db, str(a.id), str(w.owner))).id == a.id
    assert await snippet_service.get_owned_snippet(db, str(a.id), str(w.other_user)) is None
