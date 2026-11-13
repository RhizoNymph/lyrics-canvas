"""Optimistic concurrency (docs/features/sync_versions.md#base_version) over HTTP against Postgres.

A ``base_version`` that is not the current version is 409 ``version_conflict`` with the entity's
current response object (a list for the bulk word endpoints and merge), and nothing changes. A
matching one behaves like no ``base_version`` at all. Content-free changes ignore it.
"""

from __future__ import annotations

import pytest

from tests.placements.db import make_user
from tests.sync.helpers import (
    canvas,
    conflict,
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


async def edited_snippet(me, canvas_id: str) -> dict:
    """A snippet at version 2."""
    s = await new_snippet(me, canvas_id, "first")
    return ok(await put_snippet(me, s["id"], canvas_id, raw_text="second"))


# ---- snippet PUT ----


async def test_stale_content_put_is_409_with_the_current_snippet_and_changes_nothing(me):
    c = await new_canvas(me)
    s = await edited_snippet(me, c["id"])
    detail = conflict(await put_snippet(me, s["id"], c["id"], raw_text="mine", position_x=99.0, base_version=1))
    current = detail["current"]
    assert current == await snippet_on(me, c["id"], s["id"])
    assert current["version"] == 2 and current["raw_text"] == "second" and current["canvas_id"] == c["id"]
    assert current["position_x"] == s["position_x"]


async def test_matching_base_version_applies_and_bumps(me):
    c = await new_canvas(me)
    s = await edited_snippet(me, c["id"])
    out = ok(await put_snippet(me, s["id"], c["id"], raw_text="mine", base_version=2))
    assert out["raw_text"] == "mine" and out["version"] == 3


async def test_layout_only_put_ignores_a_stale_base_version(me):
    c = await new_canvas(me)
    s = await edited_snippet(me, c["id"])
    out = ok(await put_snippet(me, s["id"], c["id"], position_x=12.0, z_index=3, base_version=1))
    assert out["position_x"] == 12.0 and out["version"] == 2


async def test_base_version_must_be_positive(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    assert (await put_snippet(me, s["id"], c["id"], raw_text="x", base_version=0)).status_code == 422


async def test_stale_put_on_a_canvas_without_the_snippet_is_still_404(me):
    a, b = await new_canvas(me, "A"), await new_canvas(me, "B")
    s = await edited_snippet(me, a["id"])
    res = await put_snippet(me, s["id"], b["id"], raw_text="x", base_version=1)
    assert res.status_code == 404 and res.json()["detail"]["error"] == "placement_not_found"


# ---- snippet deletes ----


async def test_stale_delete_everywhere_is_409_and_keeps_the_snippet(me):
    c = await new_canvas(me)
    s = await edited_snippet(me, c["id"])
    detail = conflict(await me.delete(f"/api/snippets/{s['id']}", params={"base_version": 1}))
    assert detail["current"]["id"] == s["id"] and detail["current"]["version"] == 2
    assert detail["current"]["canvas_id"] == c["id"]
    assert await snippet_on(me, c["id"], s["id"]) is not None
    ok(await me.delete(f"/api/snippets/{s['id']}", params={"base_version": 2}), 204)
    assert await snippet_on(me, c["id"], s["id"]) is None
    assert (await me.delete(f"/api/snippets/{s['id']}", params={"base_version": 2})).status_code == 404


async def test_remove_from_canvas_checks_only_the_last_placement(me):
    a, b = await new_canvas(me, "A"), await new_canvas(me, "B")
    s = await edited_snippet(me, a["id"])
    ok(await me.post(f"/api/canvases/{a['id']}/snippets/transfer", json={"snippet_ids": [s["id"]], "target_canvas_id": b["id"], "mode": "show"}))
    # another placement is left: layout only, the stale version is ignored
    assert ok(await me.delete(f"/api/canvases/{a['id']}/snippets/{s['id']}", params={"base_version": 1})) == {"snippet_deleted": False}
    # the last placement: deleting for good is a content change
    detail = conflict(await me.delete(f"/api/canvases/{b['id']}/snippets/{s['id']}", params={"base_version": 1}))
    assert detail["current"]["canvas_id"] == b["id"] and detail["current"]["version"] == 2
    assert await snippet_on(me, b["id"], s["id"]) is not None
    assert ok(await me.delete(f"/api/canvases/{b['id']}/snippets/{s['id']}", params={"base_version": 2})) == {"snippet_deleted": True}


# ---- tag links ----


async def test_stale_tag_link_and_unlink_are_409_with_the_snippet(me):
    c = await new_canvas(me)
    s = await edited_snippet(me, c["id"])
    t = await new_tag(me)
    detail = conflict(await me.post(f"/api/snippets/{s['id']}/tags", params={"tag_id": t["id"]}, json={"base_version": 1}))
    assert detail["current"]["id"] == s["id"] and detail["current"]["tags"] == []
    linked = ok(await me.post(f"/api/snippets/{s['id']}/tags", params={"tag_id": t["id"]}, json={"base_version": 2}))
    assert linked == {"ok": True, "version": 3}
    detail = conflict(await me.delete(f"/api/snippets/{s['id']}/tags/{t['id']}", params={"base_version": 2}))
    assert [x["id"] for x in detail["current"]["tags"]] == [t["id"]] and detail["current"]["version"] == 3
    assert ok(await me.delete(f"/api/snippets/{s['id']}/tags/{t['id']}", params={"base_version": 3})) == {"ok": True, "version": 4}


# ---- canvases ----


async def test_canvas_title_checks_base_version(me):
    c = await new_canvas(me)
    renamed = ok(await me.put(f"/api/canvases/{c['id']}", json={"title": "Two"}))
    detail = conflict(await me.put(f"/api/canvases/{c['id']}", json={"title": "Mine", "viewport_state": {"x": 9}, "base_version": 1}))
    assert detail["current"] == {k: v for k, v in (await canvas(me, c["id"])).items() if k not in ("snippets", "groups")}
    assert detail["current"]["title"] == "Two" and detail["current"]["version"] == 2
    assert (await canvas(me, c["id"]))["viewport_state"] == renamed["viewport_state"]
    assert ok(await me.put(f"/api/canvases/{c['id']}", json={"title": "Mine", "base_version": 2}))["version"] == 3


async def test_viewport_only_canvas_put_ignores_base_version(me):
    c = await new_canvas(me)
    ok(await me.put(f"/api/canvases/{c['id']}", json={"title": "Two"}))
    out = ok(await me.put(f"/api/canvases/{c['id']}", json={"viewport_state": {"x": 1, "y": 2, "zoom": 1}, "base_version": 1}))
    assert out["viewport_state"] == {"x": 1, "y": 2, "zoom": 1}


async def test_stale_canvas_delete_is_409(me):
    c = await new_canvas(me)
    s = await new_snippet(me, c["id"])
    ok(await me.put(f"/api/canvases/{c['id']}", json={"title": "Two"}))
    detail = conflict(await me.delete(f"/api/canvases/{c['id']}", params={"base_version": 1}))
    assert detail["current"]["version"] == 2
    assert await snippet_on(me, c["id"], s["id"]) is not None
    ok(await me.delete(f"/api/canvases/{c['id']}", params={"base_version": 2}), 204)
    assert (await me.get(f"/api/canvases/{c['id']}")).status_code == 404


# ---- tags ----


async def test_tag_update_and_delete_check_base_version(me):
    t = await new_tag(me)
    ok(await me.put(f"/api/tags/{t['id']}", json={"color": "#00ff00"}))
    detail = conflict(await me.put(f"/api/tags/{t['id']}", json={"name": "mine", "base_version": 1}))
    assert detail["current"] == await tag(me, t["id"])
    assert detail["current"]["name"] == "love" and detail["current"]["version"] == 2
    conflict(await me.delete(f"/api/tags/{t['id']}", params={"base_version": 1}))
    assert await tag(me, t["id"]) is not None
    assert ok(await me.put(f"/api/tags/{t['id']}", json={"name": "mine", "base_version": 2}))["version"] == 3
    ok(await me.delete(f"/api/tags/{t['id']}", params={"base_version": 3}), 204)
    assert await tag(me, t["id"]) is None


# ---- word bank ----


async def test_word_rename_checks_base_version(me):
    w = (await new_words(me, "moon"))["created"][0]
    ok(await me.put(f"/api/word-bank/{w['id']}", json={"text": "Moon"}))
    detail = conflict(await me.put(f"/api/word-bank/{w['id']}", json={"text": "mine", "base_version": 1}))
    assert detail["current"] == await word(me, w["id"])
    assert detail["current"]["text"] == "Moon"
    assert ok(await me.put(f"/api/word-bank/{w['id']}", json={"text": "mine", "base_version": 2}))["version"] == 3


async def test_bulk_delete_with_a_stale_word_is_409_with_a_list_and_deletes_nothing(me):
    words = (await new_words(me, "moon", "june", "spoon"))["created"]
    moon, june, spoon = words[0], words[1], words[2]
    ok(await me.put(f"/api/word-bank/{june['id']}", json={"text": "June"}))
    body = {"ids": [moon["id"], june["id"], spoon["id"]], "base_versions": {moon["id"]: 1, june["id"]: 1}}
    detail = conflict(await me.post("/api/word-bank/delete", json=body))
    assert isinstance(detail["current"], list)
    assert [w["id"] for w in detail["current"]] == [june["id"]] and detail["current"][0]["version"] == 2
    assert len(ok(await me.get("/api/word-bank"))) == 3
    body["base_versions"][june["id"]] = 2
    ok(await me.post("/api/word-bank/delete", json=body), 204)
    assert ok(await me.get("/api/word-bank")) == []


async def test_bulk_tags_with_a_stale_word_is_409_with_a_list_and_changes_nothing(me):
    t = await new_tag(me)
    moon, june = (await new_words(me, "moon", "june"))["created"]
    ok(await me.put(f"/api/word-bank/{moon['id']}", json={"text": "Moon"}))
    body = {"word_ids": [moon["id"], june["id"]], "tag_id": t["id"], "op": "link", "base_versions": {moon["id"]: 1, june["id"]: 1}}
    detail = conflict(await me.post("/api/word-bank/tags", json=body))
    assert [w["id"] for w in detail["current"]] == [moon["id"]]
    assert (await word(me, june["id"]))["tags"] == [] and (await word(me, june["id"]))["version"] == 1
    body["base_versions"] = {moon["id"]: 2, june["id"]: 1}
    out = ok(await me.post("/api/word-bank/tags", json=body))["words"]
    assert [w["version"] for w in out] == [3, 2]


# ---- merge ----


async def test_merge_with_a_stale_original_is_409_with_a_list_and_changes_nothing(me):
    c = await new_canvas(me)
    one = await new_snippet(me, c["id"], "one", position_y=0.0)
    two = await edited_snippet(me, c["id"])
    body = {"snippet_ids": [one["id"], two["id"]], "mode": "vertical", "base_versions": {one["id"]: 1, two["id"]: 1}}
    detail = conflict(await me.post(f"/api/canvases/{c['id']}/merge", json=body))
    assert [s["id"] for s in detail["current"]] == [two["id"]]
    assert detail["current"][0] == await snippet_on(me, c["id"], two["id"])
    assert len((await canvas(me, c["id"]))["snippets"]) == 2
    body["base_versions"][two["id"]] = 2
    merged = ok(await me.post(f"/api/canvases/{c['id']}/merge", json=body))
    assert sorted(merged["deleted_ids"]) == sorted([one["id"], two["id"]])


async def test_without_base_version_everything_behaves_as_before(me):
    c = await new_canvas(me)
    s = await edited_snippet(me, c["id"])
    assert ok(await put_snippet(me, s["id"], c["id"], raw_text="third"))["version"] == 3
    t = await new_tag(me)
    ok(await me.put(f"/api/tags/{t['id']}", json={"name": "x"}))
    ok(await me.delete(f"/api/tags/{t['id']}"), 204)
    ok(await me.delete(f"/api/snippets/{s['id']}"), 204)
    ok(await me.delete(f"/api/canvases/{c['id']}"), 204)
