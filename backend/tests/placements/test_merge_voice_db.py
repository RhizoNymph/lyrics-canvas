"""Merge and voice takes under placements, on Postgres (see tests/tags/db.py).

Merge: an original also placed elsewhere loses only this canvas's placement and keeps its
recordings; an original only here is deleted and its recordings move to the merged snippet.
Voice: a new take is placed on the take's canvas; an append needs a placement there.
"""

from __future__ import annotations

import pytest

from app.models.recording import SplitMode
from app.services import recording_service, snippet_service
from app.services.voice.errors import SnippetNotFoundError
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

FIELDS = {"raw_text": "a\nb", "grid_data": {"lines": []}, "position_x": 5.0, "position_y": 6.0, "width": 320.0, "height": 90.0}


async def test_merge_keeps_shared_originals_elsewhere_and_deletes_the_others(db):
    w = await make_world(db)
    shared = await place_new(db, w.song, w.owner, "a", position_y=0.0)
    only = await place_new(db, w.song, w.owner, "b", position_y=100.0)
    await show_on(db, w.song, w.drafts, w.owner, shared.id)
    shared_rec, only_rec = await add_recording(db, shared.id), await add_recording(db, only.id)

    originals = await snippet_service.get_canvas_snippets(db, w.song, str(w.owner), [shared.id, only.id])
    merged = await snippet_service.replace_with_merged(
        db, w.song, FIELDS, [], originals, reparent=recording_service.reassign_recordings
    )

    assert merged.canvas_id == w.song and merged.owner_id == w.owner
    assert (merged.raw_text, merged.position_x, merged.position_y, merged.width) == ("a\nb", 5.0, 6.0, 320.0)
    assert set(await placements_of(db, merged.id)) == {w.song}
    # the shared original survives on drafts, unchanged, with its recordings
    assert set(await placements_of(db, shared.id)) == {w.drafts}
    assert await recordings_of(db, shared.id) == {shared_rec}
    # the original that was only here is gone; its recordings moved to the merged snippet
    assert not await snippet_exists(db, only.id)
    assert await recordings_of(db, merged.id) == {only_rec}
    assert await snippets_without_placement(db) == 0


async def test_merge_reads_only_snippets_placed_on_the_canvas(db):
    w = await make_world(db)
    here = await place_new(db, w.song, w.owner, "a")
    there = await place_new(db, w.drafts, w.owner, "b")
    found = await snippet_service.get_canvas_snippets(db, w.song, str(w.owner), [here.id, there.id])
    assert [s.id for s in found] == [here.id]
    assert await snippet_service.get_canvas_snippets(db, w.song, str(w.other_user), [here.id]) == []


def _recording() -> recording_service.NewRecording:
    return recording_service.NewRecording(mime_type="audio/ogg", duration_ms=10, audio=b"\x00", transcript="x", split_mode=SplitMode.taps)


async def test_a_new_take_is_placed_on_the_takes_canvas(db):
    w = await make_world(db)
    placed, row = await recording_service.create_snippet_with_recording(
        db, w.song, w.owner, position_x=3.0, position_y=4.0, lines=["hello there"], recording=_recording()
    )
    assert placed.owner_id == w.owner and placed.canvas_id == w.song
    assert (placed.position_x, placed.position_y) == (3.0, 4.0)
    assert row.snippet_id == placed.id
    assert set(await placements_of(db, placed.id)) == {w.song}


async def test_append_needs_a_placement_on_the_takes_canvas_and_resizes_only_that_one(db):
    w = await make_world(db)
    a_id = (await place_new(db, w.song, w.owner, "cat", width=111.0, height=22.0)).id
    assert not await recording_service.snippet_in_canvas(db, a_id, w.drafts, w.owner)
    with pytest.raises(SnippetNotFoundError):
        await recording_service.append_with_recording(db, w.drafts, w.owner, a_id, lines=["dog"], recording=_recording())
    await db.rollback()

    await show_on(db, w.song, w.drafts, w.owner, a_id)
    assert await recording_service.snippet_in_canvas(db, a_id, w.drafts, w.owner)
    assert not await recording_service.snippet_in_canvas(db, a_id, w.drafts, w.other_user)
    placed, _ = await recording_service.append_with_recording(db, w.drafts, w.owner, a_id, lines=["dog", "bat"], recording=_recording())
    assert placed.canvas_id == w.drafts
    assert placed.raw_text == "cat\ndog\nbat"
    on = await placements_of(db, a_id)
    assert (on[w.song].width, on[w.song].height) == (111.0, 22.0)  # the other canvas keeps its size
    assert (on[w.drafts].width, on[w.drafts].height) == (placed.width, placed.height)
