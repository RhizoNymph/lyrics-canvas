"""Client-chosen ids on creates (docs/features/sync_versions.md#client-ids) over HTTP against
Postgres: a new id creates the entity with it; a retry of the identical create returns the same
entity with the normal status; a different payload, or another user's row, is 409 ``id_conflict``;
a malformed id is 422."""

from __future__ import annotations

import uuid

import pytest

from tests.placements.db import make_user
from tests.sync.helpers import canvas, conflict, new_canvas, new_snippet, new_tag, ok, snippet_on
from tests.tags.db import requires_db

pytestmark = requires_db


@pytest.fixture
async def users(db, api):
    me, other = await make_user(db), await make_user(db)
    await db.commit()
    return api(me), api(other)


def new_id() -> str:
    return str(uuid.uuid4())


# ---- canvases ----


async def test_canvas_create_with_id_is_idempotent(users):
    me, _ = users
    cid = new_id()
    first = ok(await me.post("/api/canvases", json={"id": cid, "title": " Song "}), 201)
    assert first["id"] == cid and first["title"] == "Song" and first["version"] == 1
    again = ok(await me.post("/api/canvases", json={"id": cid, "title": "Song"}), 201)
    assert again == first
    assert [c["id"] for c in ok(await me.get("/api/canvases"))] == [cid]


async def test_canvas_id_conflicts(users):
    me, other = users
    cid = new_id()
    ok(await me.post("/api/canvases", json={"id": cid, "title": "Song"}), 201)
    detail = conflict(await me.post("/api/canvases", json={"id": cid, "title": "Other"}), "id_conflict")
    assert "current" not in detail
    conflict(await other.post("/api/canvases", json={"id": cid, "title": "Song"}), "id_conflict")
    assert ok(await other.get("/api/canvases")) == []


async def test_malformed_ids_are_422(users):
    me, _ = users
    assert (await me.post("/api/canvases", json={"id": "nope", "title": "x"})).status_code == 422
    c = await new_canvas(me)
    assert (await me.post(f"/api/canvases/{c['id']}/snippets", json={"id": "nope"})).status_code == 422
    assert (await me.post("/api/tags", json={"id": "nope", "name": "x"})).status_code == 422
    assert (await me.post("/api/word-bank", json={"words": [{"id": "nope", "text": "x"}]})).status_code == 422


# ---- snippets ----


async def test_snippet_create_with_id_is_idempotent(users):
    me, _ = users
    c = await new_canvas(me)
    sid = new_id()
    body = {"id": sid, "raw_text": "hello world", "snippet_type": "chorus", "position_x": 10.0}
    first = ok(await me.post(f"/api/canvases/{c['id']}/snippets", json=body), 201)
    assert first["id"] == sid and first["version"] == 1
    again = ok(await me.post(f"/api/canvases/{c['id']}/snippets", json=body), 201)
    assert again == first
    assert [s["id"] for s in (await canvas(me, c["id"]))["snippets"]] == [sid]


async def test_snippet_retry_with_a_supplied_grid_is_idempotent(users):
    me, _ = users
    c = await new_canvas(me)
    grid = {"lines": [{"cells": [{"syllable": "hey", "stress": 1, "word_index": 0}, {"rest": True}], "syllable_count": 1, "raw_text": "hey _"}]}
    body = {"id": new_id(), "raw_text": "hey _", "grid_data": grid}
    first = ok(await me.post(f"/api/canvases/{c['id']}/snippets", json=body), 201)
    assert ok(await me.post(f"/api/canvases/{c['id']}/snippets", json=body), 201)["id"] == first["id"]
    body["grid_data"] = {"lines": []}
    conflict(await me.post(f"/api/canvases/{c['id']}/snippets", json=body), "id_conflict")


async def test_snippet_id_conflicts(users):
    me, other = users
    c, d = await new_canvas(me, "A"), await new_canvas(me, "B")
    s = await new_snippet(me, c["id"], "hello", id=new_id())
    conflict(await me.post(f"/api/canvases/{c['id']}/snippets", json={"id": s["id"], "raw_text": "changed"}), "id_conflict")
    conflict(await me.post(f"/api/canvases/{c['id']}/snippets", json={"id": s["id"], "raw_text": "hello", "snippet_type": "hook"}), "id_conflict")
    # the same content on a canvas the snippet is not on is not the same create
    conflict(await me.post(f"/api/canvases/{d['id']}/snippets", json={"id": s["id"], "raw_text": "hello"}), "id_conflict")
    theirs = await new_canvas(other)
    conflict(await other.post(f"/api/canvases/{theirs['id']}/snippets", json={"id": s["id"], "raw_text": "hello"}), "id_conflict")
    assert (await canvas(other, theirs["id"]))["snippets"] == []


async def test_snippet_create_with_id_on_a_foreign_canvas_is_404(users):
    me, other = users
    theirs = await new_canvas(other)
    assert (await me.post(f"/api/canvases/{theirs['id']}/snippets", json={"id": new_id(), "raw_text": "x"})).status_code == 404


# ---- tags ----


async def test_tag_create_with_id_is_idempotent(users):
    me, _ = users
    tid = new_id()
    first = ok(await me.post("/api/tags", json={"id": tid, "name": "love", "color": "#FF0000"}), 201)
    assert first["id"] == tid
    again = ok(await me.post("/api/tags", json={"id": tid, "name": "love", "color": "#FF0000"}), 201)
    assert again == first
    assert len(ok(await me.get("/api/tags"))) == 1


async def test_tag_id_conflicts_and_name_taken_is_unchanged(users):
    me, other = users
    t = await new_tag(me, "love", id=new_id())
    conflict(await me.post("/api/tags", json={"id": t["id"], "name": "love", "color": "#123456"}), "id_conflict")
    conflict(await other.post("/api/tags", json={"id": t["id"], "name": "love"}), "id_conflict")
    res = await me.post("/api/tags", json={"id": new_id(), "name": "LOVE"})
    assert res.status_code == 409 and res.json()["detail"]["error"] == "tag_name_taken"


# ---- word bank ----


async def test_word_add_with_ids_is_idempotent(users):
    me, _ = users
    moon, june = new_id(), new_id()
    body = {"words": [{"id": moon, "text": "moon"}, {"id": june, "text": "june"}, "spoon"]}
    first = ok(await me.post("/api/word-bank", json=body), 201)
    assert [w["id"] for w in first["created"]][:2] == [moon, june] and first["existing"] == []
    again = ok(await me.post("/api/word-bank", json=body), 201)
    assert [w["id"] for w in again["created"]][:2] == [moon, june]
    # the plain "spoon" is now in the bank: it comes back as existing, as before
    assert [w["text"] for w in again["existing"]] == ["spoon"]
    assert len(ok(await me.get("/api/word-bank"))) == 3


async def test_word_add_with_an_id_for_text_already_in_the_bank_is_existing(users):
    me, _ = users
    stored = ok(await me.post("/api/word-bank", json={"words": ["moon"]}), 201)["created"][0]
    out = ok(await me.post("/api/word-bank", json={"words": [{"id": new_id(), "text": "Moon"}]}), 201)
    assert out["created"] == [] and [w["id"] for w in out["existing"]] == [stored["id"]]


async def test_word_id_conflicts_change_nothing(users):
    me, other = users
    wid = new_id()
    ok(await me.post("/api/word-bank", json={"words": [{"id": wid, "text": "moon"}]}), 201)
    conflict(await me.post("/api/word-bank", json={"words": ["june", {"id": wid, "text": "spoon"}]}), "id_conflict")
    conflict(await other.post("/api/word-bank", json={"words": [{"id": wid, "text": "moon"}]}), "id_conflict")
    same = new_id()
    conflict(await me.post("/api/word-bank", json={"words": [{"id": same, "text": "a"}, {"id": same, "text": "b"}]}), "id_conflict")
    assert [w["text"] for w in ok(await me.get("/api/word-bank"))] == ["moon"]
    assert ok(await other.get("/api/word-bank")) == []


# ---- merge ----


async def merge_body(me, canvas_id: str, merged_id: str) -> dict:
    one = await new_snippet(me, canvas_id, "one", position_y=0.0)
    two = await new_snippet(me, canvas_id, "two", position_y=100.0)
    return {"snippet_ids": [one["id"], two["id"]], "mode": "vertical", "merged_id": merged_id}


async def test_merge_with_merged_id_is_idempotent(users):
    me, _ = users
    c = await new_canvas(me)
    mid = new_id()
    body = await merge_body(me, c["id"], mid)
    first = ok(await me.post(f"/api/canvases/{c['id']}/merge", json=body))
    assert first["snippet"]["id"] == mid and first["snippet"]["version"] == 1
    again = ok(await me.post(f"/api/canvases/{c['id']}/merge", json=body))
    assert again["snippet"] == first["snippet"]
    assert sorted(again["deleted_ids"]) == sorted(body["snippet_ids"])
    assert [s["id"] for s in (await canvas(me, c["id"]))["snippets"]] == [mid]


async def test_merge_id_conflicts(users):
    me, other = users
    c = await new_canvas(me)
    existing = await new_snippet(me, c["id"], "unrelated", position_y=500.0)
    body = await merge_body(me, c["id"], existing["id"])
    # an unrelated snippet with that id: the originals are still here, so it is not this merge
    conflict(await me.post(f"/api/canvases/{c['id']}/merge", json=body), "id_conflict")
    assert len((await canvas(me, c["id"]))["snippets"]) == 3
    theirs = await new_canvas(other)
    their_body = await merge_body(other, theirs["id"], existing["id"])
    conflict(await other.post(f"/api/canvases/{theirs['id']}/merge", json=their_body), "id_conflict")
    assert await snippet_on(me, c["id"], existing["id"]) is not None
