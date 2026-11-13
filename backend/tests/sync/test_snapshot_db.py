"""``GET /api/sync/snapshot`` (docs/features/sync_versions.md#snapshot) against Postgres: all of the
caller's data in one response, each snippet once with its placements listed separately, and a
fixed number of queries whatever the data size."""

from __future__ import annotations

from datetime import datetime

import pytest

from tests.placements.db import make_user
from tests.sync.helpers import canvas, new_canvas, new_snippet, new_tag, new_words, ok
from tests.tags.db import requires_db

pytestmark = requires_db

SNAPSHOT_KEYS = ["server_time", "canvases", "placements", "snippets", "tags", "tag_presets", "word_bank"]
PLACEMENT_KEYS = {"snippet_id", "canvas_id", "placement_id", "position_x", "position_y", "width", "height", "z_index", "group_id"}
SNIPPET_KEYS = {
    "id", "snippet_type", "grid_mode", "beats_per_line", "grid_data", "raw_text", "phonetics", "derived_from",
    "created_at", "updated_at", "content_updated_at", "version", "tag_ids",
}
PER_CANVAS = {"placement_id", "canvas_id", "position_x", "position_y", "width", "height", "z_index", "group_id", "canvases", "tags"}


@pytest.fixture
async def users(db, api):
    me, other = await make_user(db), await make_user(db)
    await db.commit()
    return api(me), api(other)


async def test_empty_snapshot(users):
    me, _ = users
    snap = ok(await me.get("/api/sync/snapshot"))
    assert list(snap) == SNAPSHOT_KEYS
    assert all(snap[k] == [] for k in SNAPSHOT_KEYS[1:])
    datetime.fromisoformat(snap["server_time"])


async def test_snapshot_holds_everything_the_app_shows(users):
    me, other = users
    a, b = await new_canvas(me, "A"), await new_canvas(me, "B")
    t = await new_tag(me, "love", color="#ff0000")
    s = await new_snippet(me, a["id"], "hello world", position_x=5.0)
    plain = await new_snippet(me, b["id"], "solo")
    ok(await me.post(f"/api/snippets/{s['id']}/tags", params={"tag_id": t["id"]}))
    ok(await me.post(f"/api/canvases/{a['id']}/snippets/transfer", json={"snippet_ids": [s["id"]], "target_canvas_id": b["id"], "mode": "show"}))
    group = ok(await me.post(f"/api/canvases/{b['id']}/groups", json={"name": "G"}), 201)
    ok(await me.put(f"/api/snippets/{s['id']}", params={"canvas_id": b["id"]}, json={"group_id": group["id"], "position_x": 70.0}))
    preset = ok(await me.post("/api/tag-presets", json={"name": "Loved", "expression": "love"}), 201)
    words = await new_words(me, "moon", tag_ids=[t["id"]])
    # someone else's data never shows
    theirs = await new_canvas(other, "Theirs")
    await new_snippet(other, theirs["id"], "not mine")
    await new_tag(other, "secret")

    snap = ok(await me.get("/api/sync/snapshot"))

    assert sorted(c["id"] for c in snap["canvases"]) == sorted([a["id"], b["id"]])
    listed = {c["id"]: c for c in ok(await me.get("/api/canvases"))}
    assert all(c == listed[c["id"]] for c in snap["canvases"])

    assert [x["id"] for x in snap["tags"]] == [t["id"]]
    assert snap["tags"] == ok(await me.get("/api/tags"))
    assert snap["tag_presets"] == [preset]
    assert snap["word_bank"] == ok(await me.get("/api/word-bank")) == words["created"]

    by_id = {x["id"]: x for x in snap["snippets"]}
    assert sorted(by_id) == sorted([s["id"], plain["id"]]) and len(snap["snippets"]) == 2
    assert all(set(x) == SNIPPET_KEYS for x in snap["snippets"])
    on_b = {x["id"]: x for x in (await canvas(me, b["id"]))["snippets"]}
    for sid, x in by_id.items():
        full = on_b[sid]
        assert x == {**{k: v for k, v in full.items() if k not in PER_CANVAS}, "tag_ids": [tg["id"] for tg in full["tags"]]}
    assert by_id[s["id"]]["tag_ids"] == [t["id"]] and by_id[s["id"]]["version"] == 2

    assert all(set(p) == PLACEMENT_KEYS for p in snap["placements"])
    placements = {(p["snippet_id"], p["canvas_id"]): p for p in snap["placements"]}
    assert set(placements) == {(s["id"], a["id"]), (s["id"], b["id"]), (plain["id"], b["id"])}
    on_a = placements[(s["id"], a["id"])]
    assert on_a["position_x"] == 5.0 and on_a["group_id"] is None and on_a["placement_id"] == s["placement_id"]
    on_b_placement = placements[(s["id"], b["id"])]
    assert on_b_placement["position_x"] == 70.0 and on_b_placement["group_id"] == group["id"]
    assert on_b_placement["placement_id"] == on_b[s["id"]]["placement_id"]
    assert on_b_placement["z_index"] == on_b[s["id"]]["z_index"] and on_b_placement["width"] == on_b[s["id"]]["width"]


async def test_snapshot_query_count_does_not_grow_with_the_data(users, statements):
    me, _ = users

    async def count() -> int:
        statements.clear()
        ok(await me.get("/api/sync/snapshot"))
        return len(statements)

    c = await new_canvas(me)
    await new_snippet(me, c["id"])
    first = await new_tag(me)
    # one word, so the word bank's tag load runs in both counts (it is skipped for an empty bank)
    await new_words(me, "moon", tag_ids=[first["id"]])
    small = await count()
    for i in range(4):
        d = await new_canvas(me, f"C{i}")
        t = await new_tag(me, f"t{i}")
        s = await new_snippet(me, d["id"], f"line {i}")
        ok(await me.post(f"/api/snippets/{s['id']}/tags", params={"tag_id": t["id"]}))
        await new_words(me, f"word{"abcd"[i]}", tag_ids=[t["id"]])
    assert await count() == small
    assert small <= 12
