"""The placement API end to end over HTTP against Postgres (see tests/tags/db.py): canvas titles,
PUT / batch-position with canvas_id, remove from canvas, delete everywhere, transfer, typed
errors, and the canvas-scoped readers (search, similarity, export, shared view, fork, rhymes)."""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import httpx
import pytest
from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.services import rhyme_suggest_service
from tests.placements.db import make_user, snippets_without_placement
from tests.tags.db import DB_URL, requires_db

pytestmark = requires_db

USER_HEADER = "x-test-user"


@pytest.fixture
async def api(db):
    """``api(user_id)`` -> an httpx client acting as that user; each request gets its own session."""
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    sessions = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    async def test_db():
        async with sessions() as session:
            yield session

    def test_user(request: Request) -> SimpleNamespace:
        return SimpleNamespace(id=uuid.UUID(request.headers[USER_HEADER]))

    app.dependency_overrides[get_db] = test_db
    app.dependency_overrides[get_current_user] = test_user
    clients: list[httpx.AsyncClient] = []

    def as_user(user_id: uuid.UUID) -> httpx.AsyncClient:
        client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test", headers={USER_HEADER: str(user_id)}
        )
        clients.append(client)
        return client

    yield as_user
    for client in clients:
        await client.aclose()
    app.dependency_overrides.clear()
    await engine.dispose()


async def _users(db) -> tuple[uuid.UUID, uuid.UUID]:
    owner, other = await make_user(db), await make_user(db)
    await db.commit()
    return owner, other


def error_of(res: httpx.Response, status: int, code: str) -> dict:
    assert res.status_code == status, res.text
    detail = res.json()["detail"]
    assert detail["error"] == code
    assert isinstance(detail["message"], str) and detail["message"]
    return detail


async def canvas(client: httpx.AsyncClient, title: str) -> str:
    res = await client.post("/api/canvases", json={"title": title})
    assert res.status_code == 201, res.text
    return res.json()["id"]


async def snippet(client: httpx.AsyncClient, canvas_id: str, text: str, **body) -> dict:
    res = await client.post(f"/api/canvases/{canvas_id}/snippets", json={"raw_text": text, **body})
    assert res.status_code == 201, res.text
    return res.json()


async def get_canvas(client: httpx.AsyncClient, canvas_id: str) -> dict:
    res = await client.get(f"/api/canvases/{canvas_id}")
    assert res.status_code == 200, res.text
    return res.json()


# ---- canvas titles ----


async def test_create_strips_and_defaults_the_title_and_rejects_long_ones(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    res = await client.post("/api/canvases", json={"title": "  My Song \n"})
    assert res.json()["title"] == "My Song"
    assert (await client.post("/api/canvases", json={"title": "   "})).json()["title"] == "Untitled Canvas"
    assert (await client.post("/api/canvases", json={})).json()["title"] == "Untitled Canvas"
    assert (await client.post("/api/canvases", json={"title": "é" * 255})).status_code == 201  # code points, not bytes
    error_of(await client.post("/api/canvases", json={"title": "x" * 256}), 422, "invalid_canvas_title")


async def test_rename_validates_and_viewport_updates_keep_working(db, api):
    owner, other = await _users(db)
    client = api(owner)
    cid = await canvas(client, "Old")
    res = await client.put(f"/api/canvases/{cid}", json={"title": "  New name  "})
    assert res.status_code == 200 and res.json()["title"] == "New name"
    for bad in ("", "   ", "y" * 256):
        error_of(await client.put(f"/api/canvases/{cid}", json={"title": bad}), 422, "invalid_canvas_title")
    res = await client.put(f"/api/canvases/{cid}", json={"viewport_state": {"x": 3, "y": 4, "zoom": 2}})
    assert res.status_code == 200
    assert res.json()["title"] == "New name" and res.json()["viewport_state"] == {"x": 3, "y": 4, "zoom": 2}
    assert (await api(other).put(f"/api/canvases/{cid}", json={"title": "mine"})).status_code == 404


# ---- snippets and layout ----


async def test_snippet_response_carries_placement_and_canvases(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "drafts")
    made = await snippet(client, song, "cat", position_x=4.0)
    assert made["canvas_id"] == song
    assert uuid.UUID(made["placement_id"])
    assert made["canvases"] == [{"id": song, "title": "Song"}]

    res = await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [made["id"]], "target_canvas_id": drafts, "mode": "show"})
    assert res.status_code == 200
    assert res.json() == {"target_canvas_id": drafts, "placed": [made["id"]], "already_on_target": [], "removed_from_source": False}

    (on_drafts,) = (await get_canvas(client, drafts))["snippets"]
    (on_song,) = (await get_canvas(client, song))["snippets"]
    assert on_drafts["id"] == on_song["id"] == made["id"]
    assert on_drafts["placement_id"] != on_song["placement_id"]
    assert on_drafts["canvas_id"] == drafts and on_song["canvas_id"] == song
    both = [{"id": drafts, "title": "drafts"}, {"id": song, "title": "Song"}]
    assert on_drafts["canvases"] == on_song["canvases"] == both


async def test_put_requires_canvas_id_and_a_placement_there(db, api):
    owner, other = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "drafts")
    made = await snippet(client, song, "cat")
    sid = made["id"]

    assert (await client.put(f"/api/snippets/{sid}", json={"position_x": 1})).status_code == 422
    assert (await client.put(f"/api/snippets/{sid}", params={"canvas_id": "nope"}, json={"position_x": 1})).status_code == 422
    error_of(await client.put(f"/api/snippets/{sid}", params={"canvas_id": drafts}, json={"position_x": 1}), 404, "placement_not_found")
    error_of(await client.put(f"/api/snippets/{sid}", params={"canvas_id": drafts}, json={"grid_data": {"lines": []}}), 404, "placement_not_found")
    error_of(await api(other).put(f"/api/snippets/{sid}", params={"canvas_id": song}, json={"position_x": 1}), 404, "placement_not_found")

    await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [sid], "target_canvas_id": drafts, "mode": "show"})
    res = await api(owner).put(f"/api/snippets/{sid}", params={"canvas_id": drafts}, json={"raw_text": "dog", "position_x": 50, "z_index": 4})
    assert res.status_code == 200, res.text
    body = res.json()
    assert (body["canvas_id"], body["raw_text"], body["position_x"], body["z_index"]) == (drafts, "dog", 50.0, 4)
    (on_song,) = (await get_canvas(client, song))["snippets"]
    assert (on_song["raw_text"], on_song["position_x"], on_song["z_index"]) == ("dog", 0.0, 0)

    group = (await client.post(f"/api/canvases/{song}/groups", json={"name": "Verse"})).json()
    error_of(await client.put(f"/api/snippets/{sid}", params={"canvas_id": drafts}, json={"group_id": group["id"]}), 404, "group_not_found")
    res = await client.put(f"/api/snippets/{sid}", params={"canvas_id": song}, json={"group_id": group["id"]})
    assert res.json()["group_id"] == group["id"]
    (listed,) = (await get_canvas(client, song))["groups"]
    assert listed["snippet_ids"] == [sid]
    assert (await get_canvas(client, drafts))["groups"] == []


async def test_batch_position_requires_canvas_id(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "drafts")
    sid = (await snippet(client, song, "cat"))["id"]
    await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [sid], "target_canvas_id": drafts, "mode": "show"})
    update = {"id": sid, "position_x": 9.0, "position_y": 8.0}
    assert (await client.patch("/api/snippets/batch-position", json={"updates": [update]})).status_code == 422
    res = await client.patch("/api/snippets/batch-position", json={"canvas_id": drafts, "updates": [update]})
    assert res.json() == {"updated": 1}
    (on_drafts,) = (await get_canvas(client, drafts))["snippets"]
    (on_song,) = (await get_canvas(client, song))["snippets"]
    assert (on_drafts["position_x"], on_drafts["position_y"]) == (9.0, 8.0)
    assert (on_song["position_x"], on_song["position_y"]) == (0.0, 0.0)


# ---- removal ----


async def test_remove_from_canvas_then_delete_everywhere(db, api):
    owner, other = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "drafts")
    shared = (await snippet(client, song, "shared"))["id"]
    lonely = (await snippet(client, song, "lonely"))["id"]
    await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [shared], "target_canvas_id": drafts, "mode": "show"})

    res = await client.delete(f"/api/canvases/{song}/snippets/{shared}")
    assert res.status_code == 200 and res.json() == {"snippet_deleted": False}
    error_of(await client.delete(f"/api/canvases/{song}/snippets/{shared}"), 404, "placement_not_found")
    error_of(await api(other).delete(f"/api/canvases/{drafts}/snippets/{shared}"), 404, "placement_not_found")
    assert (await api(owner).delete(f"/api/canvases/{song}/snippets/{lonely}")).json() == {"snippet_deleted": True}
    assert [s["id"] for s in (await get_canvas(client, drafts))["snippets"]] == [shared]
    assert (await get_canvas(client, song))["snippets"] == []

    await client.post(f"/api/canvases/{drafts}/snippets/transfer", json={"snippet_ids": [shared], "target_canvas_id": song, "mode": "show"})
    assert (await api(other).delete(f"/api/snippets/{shared}")).status_code == 404
    assert (await api(owner).delete(f"/api/snippets/{shared}")).status_code == 204
    assert (await get_canvas(client, drafts))["snippets"] == [] == (await get_canvas(client, song))["snippets"]
    assert await snippets_without_placement(db) == 0


# ---- transfer ----


async def test_transfer_errors(db, api):
    owner, other = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "drafts")
    theirs = await canvas(api(other), "Theirs")
    sid = (await snippet(client, song, "cat"))["id"]
    url = f"/api/canvases/{song}/snippets/transfer"

    error_of(await client.post(url, json={"snippet_ids": [sid], "target_canvas_id": song, "mode": "move"}), 422, "same_canvas")
    error_of(await client.post(url, json={"snippet_ids": [sid], "target_canvas_id": theirs, "mode": "move"}), 404, "canvas_not_found")
    error_of(await client.post(f"/api/canvases/{theirs}/snippets/transfer", json={"snippet_ids": [sid], "target_canvas_id": drafts, "mode": "move"}), 404, "canvas_not_found")
    error_of(await client.post(url, json={"snippet_ids": [sid, str(uuid.uuid4())], "target_canvas_id": drafts, "mode": "move"}), 404, "placement_not_found")
    for bad in ({"snippet_ids": [], "target_canvas_id": drafts, "mode": "move"},
                {"snippet_ids": [str(uuid.uuid4()) for _ in range(501)], "target_canvas_id": drafts, "mode": "move"},
                {"snippet_ids": [sid], "target_canvas_id": drafts, "mode": "copy"}):
        assert (await client.post(url, json=bad)).status_code == 422
    # nothing changed
    assert [s["id"] for s in (await get_canvas(client, song))["snippets"]] == [sid]
    assert (await get_canvas(client, drafts))["snippets"] == []

    res = await client.post(url, json={"snippet_ids": [sid], "target_canvas_id": drafts, "mode": "move"})
    assert res.json() == {"target_canvas_id": drafts, "placed": [sid], "already_on_target": [], "removed_from_source": True}
    assert (await get_canvas(client, song))["snippets"] == []


# ---- canvas-scoped readers ----


async def test_search_similarity_and_export_read_the_canvas_placements(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "drafts")
    shared = (await snippet(client, song, "the midnight train is rolling"))["id"]
    on_drafts = (await snippet(client, drafts, "the midnight sun is rising"))["id"]
    await snippet(client, song, "a song only line")
    await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [shared], "target_canvas_id": drafts, "mode": "show"})

    found = (await client.get(f"/api/canvases/{drafts}/search", params={"q": "midnight"})).json()
    assert {r["snippet_id"] for r in found} == {shared, on_drafts}
    assert {r["snippet_id"] for r in (await client.get(f"/api/canvases/{song}/search", params={"q": "midnight"})).json()} == {shared}

    similar = (await client.get(f"/api/snippets/{shared}/similar", params={"canvas_id": drafts})).json()
    assert [r["snippet_id"] for r in similar] == [on_drafts]
    everywhere = (await client.get(f"/api/snippets/{shared}/similar")).json()
    assert {r["snippet_id"] for r in everywhere} >= {on_drafts}
    error_of(await client.get(f"/api/snippets/{on_drafts}/similar", params={"canvas_id": song}), 404, "placement_not_found")

    md = (await client.post(f"/api/canvases/{drafts}/export", json={"format": "markdown"})).text
    assert "the midnight train is rolling" in md and "the midnight sun is rising" in md
    assert "a song only line" not in md


async def test_shared_view_lists_only_the_shared_canvas(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "secret drafts")
    sid = (await snippet(client, song, "cat"))["id"]
    await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [sid], "target_canvas_id": drafts, "mode": "show"})
    token = (await client.post(f"/api/canvases/{song}/share")).json()["share_token"]
    shared = (await client.get(f"/api/canvases/shared/{token}")).json()
    (s,) = shared["snippets"]
    assert s["canvas_id"] == song
    assert s["canvases"] == [{"id": song, "title": "Song"}]


async def test_fork_copies_content_into_new_snippets_of_the_forker(db, api):
    owner, other = await _users(db)
    client = api(owner)
    song = await canvas(client, "Song")
    made = await snippet(client, song, "cat sat", position_x=12.0, position_y=34.0)
    await client.put(f"/api/snippets/{made['id']}", params={"canvas_id": song}, json={"z_index": 6})
    await client.post(f"/api/canvases/{song}/share")

    forker = api(other)
    res = await forker.post(f"/api/canvases/{song}/fork")
    assert res.status_code == 200, res.text
    fork = res.json()
    assert fork["title"] == "Song (fork)" and fork["owner_id"] == str(other)
    (copy,) = (await get_canvas(forker, fork["id"]))["snippets"]
    assert copy["id"] != made["id"]
    assert (copy["raw_text"], copy["position_x"], copy["position_y"], copy["z_index"]) == ("cat sat", 12.0, 34.0, 6)
    assert copy["canvases"] == [{"id": fork["id"], "title": "Song (fork)"}]
    assert copy["phonetics"] == made["phonetics"]
    assert copy["content_updated_at"] == made["content_updated_at"]
    # the original is untouched and still only the owner's
    (original,) = (await get_canvas(api(owner), song))["snippets"]
    assert original["canvases"] == [{"id": song, "title": "Song"}]
    error_of(await api(other).put(f"/api/snippets/{made['id']}", params={"canvas_id": fork["id"]}, json={"z_index": 1}), 404, "placement_not_found")


async def test_fork_title_fits_the_column(db, api):
    owner, other = await _users(db)
    client = api(owner)
    song = await canvas(client, "z" * 255)
    await client.post(f"/api/canvases/{song}/share")
    fork = (await api(other).post(f"/api/canvases/{song}/fork")).json()
    assert len(fork["title"]) == 255 and fork["title"].endswith(" (fork)")


async def test_rhyme_notes_count_a_shared_snippet_once(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song, drafts, third = await canvas(client, "Song"), await canvas(client, "drafts"), await canvas(client, "third")
    sid = (await snippet(client, song, "black cat"))["id"]
    for target in (drafts, third):
        await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [sid], "target_canvas_id": target, "mode": "show"})
    records = await rhyme_suggest_service.DbSnippetRepository(db).user_snippets(str(owner))
    assert [r.id for r in records] == [sid]
    assert records[0].owner_id == str(owner)
    rhymes = (await client.get("/api/nlp/rhymes", params={"word": "hat"})).json()["snippets"]
    assert [r["snippet_id"] for r in rhymes if r["word"] == "cat"] == [sid]


async def test_canvas_delete_over_http_keeps_shared_snippets(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    song, drafts = await canvas(client, "Song"), await canvas(client, "drafts")
    shared = (await snippet(client, song, "shared"))["id"]
    await snippet(client, song, "lonely")
    await client.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [shared], "target_canvas_id": drafts, "mode": "show"})
    assert (await client.delete(f"/api/canvases/{song}")).status_code == 204
    (left,) = (await get_canvas(client, drafts))["snippets"]
    assert left["id"] == shared and left["canvases"] == [{"id": drafts, "title": "drafts"}]
    assert await snippets_without_placement(db) == 0
