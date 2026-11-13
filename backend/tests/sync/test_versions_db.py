"""Content versions (docs/features/sync_versions.md#versions) over HTTP against Postgres: every
entity starts at 1 and its version goes up by exactly 1 per content change, in the same
transaction; layout, viewport, placements and phonetics-only recomputes never bump it."""

from __future__ import annotations

import uuid

import pytest

from app.models.recording import SplitMode
from app.services import recording_service
from app.services.recording_service import NewRecording
from app.services.nlp.syllabifier import syllabify_text
from tests.placements.db import make_user
from tests.sync.helpers import (
    canvas,
    new_canvas,
    new_snippet,
    new_tag,
    new_words,
    ok,
    put_snippet,
    snippet_on,
    tag,
    word,
)
from tests.tags.db import requires_db

pytestmark = requires_db


@pytest.fixture
async def me(db, api):
    user = await make_user(db)
    await db.commit()
    return api(user)


# ---- new entities ----


async def test_new_entities_start_at_version_1(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    t = await new_tag(me)
    words = await new_words(me, "moon")
    assert c["version"] == 1 and s["version"] == 1 and t["version"] == 1
    assert words["created"][0]["version"] == 1
    detail = await canvas(me, c["id"])
    assert detail["version"] == 1 and detail["snippets"][0]["version"] == 1
    assert ok(await me.get("/api/canvases"))[0]["version"] == 1


# ---- snippets ----


@pytest.mark.parametrize(
    "body",
    [
        {"raw_text": "goodbye moon"},
        {"snippet_type": "chorus"},
        {"grid_mode": "fixed"},
        {"beats_per_line": 8},
        {"grid_data": syllabify_text("hel lo world")},
    ],
)
async def test_each_content_field_bumps_by_one(me, body):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    out = ok(await put_snippet(me, s["id"], c["id"], **body))
    assert out["version"] == 2
    assert (await snippet_on(me, c["id"], s["id"]))["version"] == 2


async def test_several_content_fields_in_one_put_bump_once(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    out = ok(await put_snippet(me, s["id"], c["id"], raw_text="a b", snippet_type="hook", position_x=40.0))
    assert out["version"] == 2


async def test_unchanged_content_does_not_bump(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"], snippet_type="verse")
    out = ok(await put_snippet(me, s["id"], c["id"], raw_text=s["raw_text"], snippet_type="verse"))
    assert out["version"] == 1


async def test_resending_the_stored_grid_recomputes_phonetics_without_a_bump(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    out = ok(await put_snippet(me, s["id"], c["id"], grid_data=s["grid_data"]))
    assert out["version"] == 1 and out["phonetics"] is not None


@pytest.mark.parametrize(
    "body", [{"position_x": 10.0, "position_y": 20.0}, {"width": 400.0, "height": 120.0}, {"z_index": 7}]
)
async def test_layout_puts_never_bump(me, body):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    assert ok(await put_snippet(me, s["id"], c["id"], **body))["version"] == 1


async def test_batch_position_transfer_and_removal_never_bump(me):
    a, b = await new_canvas(me, "A"), await new_canvas(me, "B")
    s = await new_snippet(me, a["id"])
    batch = {"canvas_id": a["id"], "updates": [{"id": s["id"], "position_x": 5.0}]}
    ok(await me.patch("/api/snippets/batch-position", json=batch))
    ok(await me.post(f"/api/canvases/{a['id']}/snippets/transfer", json={"snippet_ids": [s["id"]], "target_canvas_id": b["id"], "mode": "show"}))
    ok(await me.delete(f"/api/canvases/{a['id']}/snippets/{s['id']}"))
    assert (await snippet_on(me, b["id"], s["id"]))["version"] == 1


async def test_group_membership_never_bumps(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    group = ok(await me.post(f"/api/canvases/{c['id']}/groups", json={"name": "G"}), 201)
    assert ok(await put_snippet(me, s["id"], c["id"], group_id=group["id"]))["version"] == 1


async def test_tag_link_and_unlink_bump_the_snippet_and_report_its_version(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    t = await new_tag(me)
    linked = ok(await me.post(f"/api/snippets/{s['id']}/tags", params={"tag_id": t["id"]}))
    assert linked == {"ok": True, "version": 2}
    # linking again changes nothing
    assert ok(await me.post(f"/api/snippets/{s['id']}/tags", params={"tag_id": t["id"]})) == {"ok": True, "version": 2}
    unlinked = ok(await me.delete(f"/api/snippets/{s['id']}/tags/{t['id']}"))
    assert unlinked == {"ok": True, "version": 3}
    assert ok(await me.delete(f"/api/snippets/{s['id']}/tags/{t['id']}")) == {"ok": True, "version": 3}
    assert (await snippet_on(me, c["id"], s["id"]))["version"] == 3


async def test_deleting_or_renaming_a_tag_does_not_bump_snippets_or_words(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    t = await new_tag(me)
    w = (await new_words(me, "moon", tag_ids=[t["id"]]))["created"][0]
    ok(await me.post(f"/api/snippets/{s['id']}/tags", params={"tag_id": t["id"]}))
    ok(await me.put(f"/api/tags/{t['id']}", json={"name": "romance"}))
    ok(await me.delete(f"/api/tags/{t['id']}"), 204)
    assert (await snippet_on(me, c["id"], s["id"]))["version"] == 2
    assert (await word(me, w["id"]))["version"] == 1


async def test_voice_append_bumps(db, me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    owner = uuid.UUID(ok(await me.get(f"/api/canvases/{c['id']}"))["owner_id"])
    recording = NewRecording(mime_type="audio/ogg", duration_ms=10, audio=b"\x00", transcript="new line", split_mode=SplitMode.taps)
    placed, _ = await recording_service.append_with_recording(
        db, uuid.UUID(c["id"]), owner, uuid.UUID(s["id"]), lines=["new line"], recording=recording
    )
    assert placed.version == 2
    assert (await snippet_on(me, c["id"], s["id"]))["version"] == 2


async def test_voice_take_creates_at_version_1(db, me):
    c = await new_canvas(me)
    owner = uuid.UUID(c["owner_id"])
    recording = NewRecording(mime_type="audio/ogg", duration_ms=10, audio=b"\x00", transcript="a", split_mode=SplitMode.taps)
    placed, _ = await recording_service.create_snippet_with_recording(
        db, uuid.UUID(c["id"]), owner, position_x=0.0, position_y=0.0, lines=["a"], recording=recording
    )
    assert placed.version == 1


async def test_merge_creates_at_version_1_and_leaves_kept_originals_alone(me):
    a, b = await new_canvas(me, "A"), await new_canvas(me, "B")
    s1 = await new_snippet(me, a["id"], "one", position_y=0.0)
    s2 = await new_snippet(me, a["id"], "two", position_y=100.0)
    ok(await me.post(f"/api/canvases/{a['id']}/snippets/transfer", json={"snippet_ids": [s1["id"]], "target_canvas_id": b["id"], "mode": "show"}))
    merged = ok(await me.post(f"/api/canvases/{a['id']}/merge", json={"snippet_ids": [s1["id"], s2["id"]], "mode": "vertical"}))
    assert merged["snippet"]["version"] == 1
    assert (await snippet_on(me, b["id"], s1["id"]))["version"] == 1


# ---- canvases ----


async def test_canvas_title_bumps_and_viewport_does_not(me):
    c = await new_canvas(me)
    assert ok(await me.put(f"/api/canvases/{c['id']}", json={"viewport_state": {"x": 3, "y": 4, "zoom": 2}}))["version"] == 1
    assert ok(await me.put(f"/api/canvases/{c['id']}", json={"title": "Song"}))["version"] == 1
    assert ok(await me.put(f"/api/canvases/{c['id']}", json={"title": "Renamed"}))["version"] == 2
    ok(await me.post(f"/api/canvases/{c['id']}/share"))
    assert (await canvas(me, c["id"]))["version"] == 2


# ---- tags ----


async def test_tag_name_and_color_bump(me):
    t = await new_tag(me)
    assert ok(await me.put(f"/api/tags/{t['id']}", json={"name": "love"}))["version"] == 1
    assert ok(await me.put(f"/api/tags/{t['id']}", json={"name": "Love"}))["version"] == 2
    assert ok(await me.put(f"/api/tags/{t['id']}", json={"color": "#00ff00"}))["version"] == 3
    assert ok(await me.put(f"/api/tags/{t['id']}", json={"name": "Lust", "color": "#0000ff"}))["version"] == 4
    assert (await tag(me, t["id"]))["version"] == 4


async def test_linking_a_tag_does_not_bump_the_tag(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    t = await new_tag(me)
    ok(await me.post(f"/api/snippets/{s['id']}/tags", params={"tag_id": t["id"]}))
    assert (await tag(me, t["id"]))["version"] == 1


# ---- word bank ----


async def test_word_rename_bumps(me):
    w = (await new_words(me, "moon"))["created"][0]
    assert ok(await me.put(f"/api/word-bank/{w['id']}", json={"text": "moon"}))["version"] == 1
    assert ok(await me.put(f"/api/word-bank/{w['id']}", json={"text": "Moon"}))["version"] == 2
    assert (await word(me, w["id"]))["version"] == 2


async def test_word_tagging_bumps_only_words_whose_links_change(me):
    t = await new_tag(me)
    tagged = (await new_words(me, "moon", tag_ids=[t["id"]]))["created"][0]
    plain = (await new_words(me, "june"))["created"][0]
    body = {"word_ids": [tagged["id"], plain["id"]], "tag_id": t["id"], "op": "link"}
    out = {w["id"]: w["version"] for w in ok(await me.post("/api/word-bank/tags", json=body))["words"]}
    assert out == {tagged["id"]: 1, plain["id"]: 2}
    body["op"] = "unlink"
    out = {w["id"]: w["version"] for w in ok(await me.post("/api/word-bank/tags", json=body))["words"]}
    assert out == {tagged["id"]: 2, plain["id"]: 3}


async def test_adding_existing_words_with_tags_bumps_the_existing_ones(me):
    t = await new_tag(me)
    first = (await new_words(me, "moon"))["created"][0]
    result = await new_words(me, "Moon", "spoon", tag_ids=[t["id"]])
    assert [w["version"] for w in result["existing"]] == [2]
    assert [w["version"] for w in result["created"]] == [1]
    # linking a tag the word already has changes nothing
    again = await new_words(me, "moon", tag_ids=[t["id"]])
    assert again["existing"][0]["id"] == first["id"] and again["existing"][0]["version"] == 2
