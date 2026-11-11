"""GET /api/search (search across the caller's canvases) and the rest-aware per-canvas search, end to
end over HTTP against Postgres (TAG_TEST_DATABASE_URL, see tests/tags/db.py)."""

from __future__ import annotations

import unicodedata
import uuid
from datetime import datetime, timedelta, timezone

import httpx
from sqlalchemy import update

from app.models.canvas import Canvas
from app.models.snippet import Snippet
from tests.placements.db import make_user
from tests.tags.db import requires_db

pytestmark = requires_db

T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


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


async def snippet(client: httpx.AsyncClient, canvas_id: str, text: str, **body) -> str:
    res = await client.post(f"/api/canvases/{canvas_id}/snippets", json={"raw_text": text, **body})
    assert res.status_code == 201, res.text
    return res.json()["id"]


async def show(client: httpx.AsyncClient, source: str, target: str, *snippet_ids: str) -> None:
    res = await client.post(
        f"/api/canvases/{source}/snippets/transfer",
        json={"snippet_ids": list(snippet_ids), "target_canvas_id": target, "mode": "show"},
    )
    assert res.status_code == 200, res.text


async def edited_at(db, snippet_id: str, minutes: int) -> None:
    await db.execute(
        update(Snippet).where(Snippet.id == uuid.UUID(snippet_id)).values(content_updated_at=T0 + timedelta(minutes=minutes))
    )
    await db.commit()


async def canvas_updated_at(db, canvas_id: str, minutes: int) -> None:
    await db.execute(update(Canvas).where(Canvas.id == uuid.UUID(canvas_id)).values(updated_at=T0 + timedelta(minutes=minutes)))
    await db.commit()


async def search(client: httpx.AsyncClient, q: str | None, **params) -> dict:
    query = dict(params)
    if q is not None:
        query["q"] = q
    res = await client.get("/api/search", params=query)
    assert res.status_code == 200, res.text
    return res.json()


# ---- validation ----


async def test_invalid_queries_are_typed_422(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    error_of(await client.get("/api/search"), 422, "invalid_search_query")
    for bad in ("", "   ", "x" * 201, " " + "y" * 201 + " "):
        error_of(await client.get("/api/search", params={"q": bad}), 422, "invalid_search_query")
    assert (await client.get("/api/search", params={"q": " " + "z" * 200 + " "})).status_code == 200


async def test_limit_bounds(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    for bad in (0, 201, -1):
        assert (await client.get("/api/search", params={"q": "a", "limit": bad})).status_code == 422
    for ok in (1, 200):
        assert (await client.get("/api/search", params={"q": "a", "limit": ok})).status_code == 200


# ---- response shape ----


async def test_response_shape(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    cid = await canvas(client, "Midnight songs")
    sid = await snippet(client, cid, "under the midnight _ sun", snippet_type="chorus")
    body = await search(client, "midnight")
    assert set(body) == {"canvases", "snippets"}
    (hit_canvas,) = body["canvases"]
    assert set(hit_canvas) == {"id", "title", "updated_at"}
    assert hit_canvas["id"] == cid and hit_canvas["title"] == "Midnight songs"
    assert isinstance(hit_canvas["updated_at"], str)
    (hit,) = body["snippets"]
    assert set(hit) == {"snippet_id", "snippet_type", "content_updated_at", "excerpt", "canvases"}
    assert hit["snippet_id"] == sid
    assert hit["snippet_type"] == "chorus"
    assert isinstance(hit["content_updated_at"], str)
    assert hit["excerpt"] == "under the midnight sun"
    assert hit["canvases"] == [{"id": cid, "title": "Midnight songs"}]


# ---- canvases ----


async def test_canvases_match_all_terms_newest_first_and_only_the_callers(db, api):
    owner, other = await _users(db)
    mine, theirs = api(owner), api(other)
    a = await canvas(mine, "Summer Song")
    b = await canvas(mine, "song of SUMMER")
    await canvas(mine, "Winter song")
    await canvas(theirs, "Summer song (theirs)")
    await canvas_updated_at(db, a, 1)
    await canvas_updated_at(db, b, 2)
    body = await search(mine, "summer SONG")
    assert [c["id"] for c in body["canvases"]] == [b, a]


async def test_canvas_title_terms_are_literal(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    pct = await canvas(client, "100% real")
    await canvas(client, "1000 real")
    under = await canvas(client, "my_song")
    await canvas(client, "myXsong")
    assert [c["id"] for c in (await search(client, "100%"))["canvases"]] == [pct]
    assert [c["id"] for c in (await search(client, "my_s"))["canvases"]] == [under]


# ---- snippets ----


async def test_snippets_match_display_text_and_only_the_callers(db, api):
    owner, other = await _users(db)
    mine, theirs = api(owner), api(other)
    cid = await canvas(mine, "Song")
    go_now = await snippet(mine, cid, "let's go_now")
    await snippet(mine, cid, "go somewhere, now")  # matches too: terms are independent
    await snippet(mine, cid, "stay here")
    their_cid = await canvas(theirs, "Song")
    await snippet(theirs, their_cid, "go now")

    found = {s["snippet_id"] for s in (await search(mine, "go now"))["snippets"]}
    assert go_now in found and len(found) == 2
    assert (await search(mine, "_"))["snippets"] == []
    assert (await search(mine, "o_n"))["snippets"] == []
    assert (await search(mine, "go_now"))["snippets"] == []


async def test_wildcards_are_escaped(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    cid = await canvas(client, "Numbers")
    hundred = await snippet(client, cid, "give 100% now")
    await snippet(client, cid, "give 1000 now")
    slash = await snippet(client, cid, "back\\slash")
    await snippet(client, cid, "backXslash")
    assert [s["snippet_id"] for s in (await search(client, "100%"))["snippets"]] == [hundred]
    assert [s["snippet_id"] for s in (await search(client, "back\\slash"))["snippets"]] == [slash]


async def test_unicode_normalisation_and_case(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    cid = await canvas(client, unicodedata.normalize("NFD", "Café"))
    sid = await snippet(client, cid, unicodedata.normalize("NFD", "Café au lait"))
    body = await search(client, "CAFÉ")
    assert [s["snippet_id"] for s in body["snippets"]] == [sid]
    assert [c["id"] for c in body["canvases"]] == [cid]


async def test_snippets_order_by_content_updated_at_then_id(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    cid = await canvas(client, "Song")
    old = await snippet(client, cid, "rain one")
    new = await snippet(client, cid, "rain two")
    tie_a = await snippet(client, cid, "rain three")
    tie_b = await snippet(client, cid, "rain four")
    await edited_at(db, old, 1)
    await edited_at(db, new, 5)
    await edited_at(db, tie_a, 3)
    await edited_at(db, tie_b, 3)
    ids = [s["snippet_id"] for s in (await search(client, "rain"))["snippets"]]
    ties = sorted([tie_a, tie_b])
    assert ids == [new, *ties, old]


async def test_snippet_lists_every_canvas_once_sorted_by_title(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    zed, alpha, beta = await canvas(client, "zed"), await canvas(client, "Alpha"), await canvas(client, "beta")
    sid = await snippet(client, zed, "echo echo")
    await show(client, zed, alpha, sid)
    await show(client, zed, beta, sid)
    (hit,) = (await search(client, "echo"))["snippets"]
    assert hit["canvases"] == [{"id": alpha, "title": "Alpha"}, {"id": beta, "title": "beta"}, {"id": zed, "title": "zed"}]


async def test_limit_applies_to_each_list(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    c1 = await canvas(client, "storm one")
    await canvas(client, "storm two")
    await canvas(client, "storm three")
    for i in range(4):
        await snippet(client, c1, f"storm {i}")
    body = await search(client, "storm", limit=2)
    assert len(body["canvases"]) == 2
    assert len(body["snippets"]) == 2
    default = await search(client, "storm")
    assert len(default["canvases"]) == 3 and len(default["snippets"]) == 4


async def test_limit_counts_confirmed_matches(db, api):
    """Candidates the SQL prefilter lets through but display text rejects never use up the limit."""
    owner, _ = await _users(db)
    client = api(owner)
    cid = await canvas(client, "Song")
    keep = await snippet(client, cid, "fire")
    await edited_at(db, keep, 1)
    for i in range(3):
        sid = await snippet(client, cid, f"_ _ line {i}")
        await edited_at(db, sid, 10 + i)
    # "_" never matches; with limit 1 we still get nothing rather than an error
    assert (await search(client, "_", limit=1))["snippets"] == []
    assert [s["snippet_id"] for s in (await search(client, "fire", limit=1))["snippets"]] == [keep]


async def test_excerpt_picks_the_line_with_the_first_term(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    cid = await canvas(client, "Song")
    await snippet(client, cid, "first line\n_ _\nthe black _ cat\nwhite night")
    (hit,) = (await search(client, "cat white"))["snippets"]
    assert hit["excerpt"] == "the black cat"


async def test_snippet_with_rests_only_text_is_never_a_hit(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    cid = await canvas(client, "Song")
    await snippet(client, cid, "_ _ _")
    assert (await search(client, "_"))["snippets"] == []


# ---- per-canvas search reads display text ----


async def test_canvas_search_matches_display_text_terms(db, api):
    owner, other = await _users(db)
    client = api(owner)
    cid = await canvas(client, "Song")
    go_now = await snippet(client, cid, "go_now")
    await snippet(client, cid, "stay")
    hundred = await snippet(client, cid, "100% sure")
    await snippet(client, cid, "1000 sure")

    def ids(res: httpx.Response) -> list[str]:
        assert res.status_code == 200, res.text
        return [r["snippet_id"] for r in res.json()]

    assert ids(await client.get(f"/api/canvases/{cid}/search", params={"q": "GO now"})) == [go_now]
    assert ids(await client.get(f"/api/canvases/{cid}/search", params={"q": "_"})) == []
    assert ids(await client.get(f"/api/canvases/{cid}/search", params={"q": "100%"})) == [hundred]
    assert ids(await api(other).get(f"/api/canvases/{cid}/search", params={"q": "go"})) == []
    (row,) = (await client.get(f"/api/canvases/{cid}/search", params={"q": "now"})).json()
    assert row == {"snippet_id": go_now, "raw_text": "go_now", "score": 1.0, "snippet_type": "freestyle"}


# ---- similarity reads display text ----


async def test_similarity_reads_display_text(db, api):
    owner, _ = await _users(db)
    client = api(owner)
    cid = await canvas(client, "Song")
    target = await snippet(client, cid, "burning heart")
    other = await snippet(client, cid, "burning_heart _")
    await snippet(client, cid, "_ _")
    res = await client.get(f"/api/snippets/{target}/similar")
    assert res.status_code == 200, res.text
    results = res.json()
    assert [r["snippet_id"] for r in results] == [other]
    assert results[0]["raw_text"] == "burning_heart _"
    assert results[0]["score"] > 0.99
