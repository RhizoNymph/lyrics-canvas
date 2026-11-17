"""Custom list order on Postgres (see tests/tags/db.py): server-assigned top ranks for every path
that inserts a placement, client ranks on create / PUT / batch-position, ``invalid_list_rank``,
byte-order comparison of ranks, and ``list_rank`` on every placement read."""

from __future__ import annotations

import uuid

from app.models.recording import SplitMode
from app.services import recording_service
from app.services.placements.list_rank import key_between
from tests.placements.db import make_world, place_new
from tests.placements.test_routes_db import _users, api, canvas, error_of, get_canvas, snippet  # noqa: F401  (api is a fixture)
from tests.tags.db import requires_db

pytestmark = requires_db


def custom_order(detail: dict) -> list[str]:
    """Snippet ids top to bottom: list_rank by byte order, then placement id."""
    rows = sorted(detail["snippets"], key=lambda s: (s["list_rank"].encode("ascii"), s["placement_id"]))
    return [s["id"] for s in rows]


def ranks(detail: dict) -> dict[str, str]:
    return {s["id"]: s["list_rank"] for s in detail["snippets"]}


async def test_new_snippets_go_to_the_top_of_the_custom_order(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    first = await snippet(client, song, "first")
    second = await snippet(client, song, "second")
    third = await snippet(client, song, "third")
    assert (first["list_rank"], second["list_rank"], third["list_rank"]) == ("a0", "Zz", "Zy")
    assert custom_order(await get_canvas(client, song)) == [third["id"], second["id"], first["id"]]


async def test_top_rank_compares_by_bytes_not_locale(db, api):
    # In a locale collation "b00" sorts before "Zz" (case folded); by bytes "Zz" < "b00".
    owner, _ = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    await snippet(client, song, "low", list_rank="b00")
    await snippet(client, song, "high", list_rank="Zz")
    top = await snippet(client, song, "top")
    assert top["list_rank"] == key_between(None, "Zz") == "Zy"


async def test_create_takes_an_explicit_rank_and_rejects_an_invalid_one(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    made = await snippet(client, song, "mine", list_rank="a0V")
    assert made["list_rank"] == "a0V"
    for bad in ("a00", "", "b0", "a0!", "a0" + "V" * 63):
        error_of(await client.post(f"/api/canvases/{song}/snippets", json={"raw_text": "x", "list_rank": bad}), 422, "invalid_list_rank")
    assert [s["id"] for s in (await get_canvas(client, song))["snippets"]] == [made["id"]]


async def test_create_with_client_id_retry_keeps_the_rank(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    sid = str(uuid.uuid4())
    body = {"id": sid, "raw_text": "offline", "list_rank": "Zx"}
    first = await client.post(f"/api/canvases/{song}/snippets", json=body)
    again = await client.post(f"/api/canvases/{song}/snippets", json=body)
    assert first.status_code == again.status_code == 201
    assert first.json()["list_rank"] == again.json()["list_rank"] == "Zx"


async def test_put_list_rank_is_layout(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    made = await snippet(client, song, "cat")
    res = await client.put(f"/api/snippets/{made['id']}", params={"canvas_id": song}, json={"list_rank": "a5"})
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["list_rank"] == "a5"
    # layout: no version bump, no content timestamp, and a stale base_version is not checked
    assert body["version"] == made["version"]
    assert body["content_updated_at"] == made["content_updated_at"]
    res = await client.put(f"/api/snippets/{made['id']}", params={"canvas_id": song}, json={"list_rank": "a6", "base_version": 99})
    assert res.status_code == 200 and res.json()["list_rank"] == "a6"
    error_of(await client.put(f"/api/snippets/{made['id']}", params={"canvas_id": song}, json={"list_rank": "zz"}), 422, "invalid_list_rank")
    error_of(
        await client.put(f"/api/snippets/{made['id']}", params={"canvas_id": song}, json={"list_rank": "a0 ", "position_x": 9.0}),
        422, "invalid_list_rank",
    )
    (stored,) = (await get_canvas(client, song))["snippets"]
    assert (stored["list_rank"], stored["position_x"]) == ("a6", made["position_x"])


async def test_rank_is_per_canvas(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "Drafts")
    made = await snippet(client, song, "cat")
    await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [made["id"]], "target_canvas_id": drafts, "mode": "show"})
    await client.put(f"/api/snippets/{made['id']}", params={"canvas_id": drafts}, json={"list_rank": "c000"})
    assert ranks(await get_canvas(client, song))[made["id"]] == "a0"
    assert ranks(await get_canvas(client, drafts))[made["id"]] == "c000"


async def test_batch_position_reranks_a_whole_canvas_in_one_call(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    a, b, c = [await snippet(client, song, t, position_x=float(i)) for i, t in enumerate(("a", "b", "c"))]
    res = await client.patch(
        "/api/snippets/batch-position",
        json={"canvas_id": song, "updates": [{"id": a["id"], "list_rank": "a0"}, {"id": b["id"], "list_rank": "a1"}, {"id": c["id"], "list_rank": "a2"}]},
    )
    assert res.status_code == 200 and res.json() == {"updated": 3}
    detail = await get_canvas(client, song)
    assert custom_order(detail) == [a["id"], b["id"], c["id"]]
    assert {s["id"]: s["position_x"] for s in detail["snippets"]} == {a["id"]: 0.0, b["id"]: 1.0, c["id"]: 2.0}
    # positions and ranks together still work
    res = await client.patch(
        "/api/snippets/batch-position", json={"canvas_id": song, "updates": [{"id": a["id"], "position_x": 50.0, "list_rank": "a3"}]}
    )
    assert res.json() == {"updated": 1}
    # one invalid rank: nothing changes
    error_of(
        await client.patch(
            "/api/snippets/batch-position",
            json={"canvas_id": song, "updates": [{"id": b["id"], "list_rank": "a9"}, {"id": c["id"], "list_rank": "a90"}]},
        ),
        422, "invalid_list_rank",
    )
    after = ranks(await get_canvas(client, song))
    assert after == {a["id"]: "a3", b["id"]: "a1", c["id"]: "a2"}


async def test_transfer_puts_placements_on_top_of_the_target_in_source_list_order(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "Drafts")
    there = await snippet(client, drafts, "already there")  # a0 on drafts
    one = await snippet(client, song, "one")  # a0
    two = await snippet(client, song, "two")  # Zz: above one
    res = await client.post(
        f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [one["id"], two["id"]], "target_canvas_id": drafts, "mode": "move"}
    )
    assert res.status_code == 200, res.text
    detail = await get_canvas(client, drafts)
    assert custom_order(detail) == [two["id"], one["id"], there["id"]]
    assert ranks(detail) == {there["id"]: "a0", two["id"]: "Zy", one["id"]: "Zz"}
    # show: also top of the target
    other = await snippet(client, song, "other")
    await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [other["id"]], "target_canvas_id": drafts, "mode": "show"})
    assert custom_order(await get_canvas(client, drafts))[0] == other["id"]


async def test_merge_keeps_the_first_blocks_rank(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    top_block = await snippet(client, song, "top", position_y=0.0)  # a0
    low_block = await snippet(client, song, "low", position_y=300.0)  # Zz
    await snippet(client, song, "unrelated")  # Zy
    await client.put(f"/api/snippets/{top_block['id']}", params={"canvas_id": song}, json={"list_rank": "a7"})
    res = await client.post(f"/api/canvases/{song}/merge", json={"snippet_ids": [low_block["id"], top_block["id"]], "mode": "vertical"})
    assert res.status_code == 200, res.text
    assert res.json()["snippet"]["list_rank"] == "a7"


async def test_voice_take_goes_to_the_top(db):
    w = await make_world(db)
    old = await place_new(db, w.song, w.owner, "old")
    assert old.list_rank == "a0"
    recording = recording_service.NewRecording(mime_type="audio/ogg", duration_ms=10, audio=b"\x00", transcript="x", split_mode=SplitMode.taps)
    placed, _ = await recording_service.create_snippet_with_recording(
        db, w.song, w.owner, position_x=0.0, position_y=0.0, lines=["hello"], recording=recording
    )
    assert placed.list_rank == "Zz"


async def test_fork_copies_the_ranks(db, api):
    owner, other = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    a = await snippet(client, song, "a")
    b = await snippet(client, song, "b")
    await client.put(f"/api/snippets/{a['id']}", params={"canvas_id": song}, json={"list_rank": "Zx"})
    await client.post(f"/api/canvases/{song}/share")
    fork = (await api(other).post(f"/api/canvases/{song}/fork")).json()
    by_text = {s["raw_text"]: s["list_rank"] for s in (await get_canvas(api(other), fork["id"]))["snippets"]}
    assert by_text == {"a": "Zx", "b": b["list_rank"]}


async def test_snapshot_and_shared_view_carry_the_rank(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    made = await snippet(client, song, "cat", list_rank="a3")
    snap = (await client.get("/api/sync/snapshot")).json()
    (placement,) = snap["placements"]
    assert placement["list_rank"] == "a3" and placement["placement_id"] == made["placement_id"]
    token = (await client.post(f"/api/canvases/{song}/share")).json()["share_token"]
    (shared,) = (await client.get(f"/api/canvases/shared/{token}")).json()["snippets"]
    assert shared["list_rank"] == "a3"
