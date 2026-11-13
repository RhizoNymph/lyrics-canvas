"""Rhyme-pattern routes end to end over HTTP against Postgres (TAG_TEST_DATABASE_URL, see
tests/tags/db.py): ownership and placement checks of ``DbPatternRepository``, detection on stored
phonetics, notes from the user's other snippets."""

from __future__ import annotations

import httpx
import pytest

from app.services import rhyme_pattern_service
from tests.placements.conftest import db, migrated  # noqa: F401  (fixtures)
from tests.placements.db import make_user
from tests.search.conftest import api  # noqa: F401  (fixture)
from tests.tags.db import requires_db

from .fakes import fake_dictionary_index
from .helpers import EXAMPLE

pytestmark = requires_db


@pytest.fixture(autouse=True)
def fake_dictionary(monkeypatch):
    monkeypatch.setattr(rhyme_pattern_service, "default_dictionary_index", fake_dictionary_index)


async def _canvas(client: httpx.AsyncClient, title: str) -> str:
    res = await client.post("/api/canvases", json={"title": title})
    assert res.status_code == 201, res.text
    return res.json()["id"]


async def _snippet(client: httpx.AsyncClient, canvas_id: str, text: str) -> str:
    res = await client.post(f"/api/canvases/{canvas_id}/snippets", json={"raw_text": text, "position_x": 0, "position_y": 0})
    assert res.status_code == 201, res.text
    return res.json()["id"]


async def test_detect_and_suggest_on_stored_snippets(db, api):  # noqa: F811
    owner, other = await make_user(db), await make_user(db)
    await db.commit()
    me, them = api(owner), api(other)
    song = await _canvas(me, "Song")
    drafts = await _canvas(me, "Drafts")
    verse = await _snippet(me, song, "\n".join(EXAMPLE))
    await _snippet(me, drafts, "heavy is the feather song")
    elsewhere = await _snippet(me, drafts, "steady\nready")

    body = {"canvas_id": song, "snippet_id": verse, "line_start": 0, "line_end": 3}
    res = await me.post("/api/nlp/rhyme-pattern/detect", json=body)
    assert res.status_code == 200, res.text
    slots = res.json()["slots"]
    assert [[m["text"] for m in s["members"]] for s in slots] == [
        ["steady", "already", "ready"],
        ["pressure", "weather", "better"],
        ["on", "wrong", "long"],
    ]

    specs = [{"id": s["id"], "members": [{k: m[k] for k in ("line_index", "word_start", "word_end")} for m in s["members"]]} for s in slots]
    res = await me.post("/api/nlp/rhyme-pattern/suggest", json={**body, "slots": specs})
    assert res.status_code == 200, res.text
    out = res.json()
    notes = [c for r in out["slots"] for c in r["candidates"] if c["source"] == "notes"]
    assert {c["text"] for c in notes} >= {"heavy", "feather", "song"}  # from a snippet on another canvas
    assert [h["text"] for h in out["line_matches"][0]["hits"]] == ["heavy", "feather", "song"]

    # the user's snippet, but not on this canvas
    res = await me.post("/api/nlp/rhyme-pattern/detect", json={**body, "snippet_id": elsewhere})
    assert res.status_code == 404
    # someone else's canvas and snippet
    assert (await them.post("/api/nlp/rhyme-pattern/detect", json=body)).status_code == 404
    their_canvas = await _canvas(them, "Theirs")
    res = await them.post("/api/nlp/rhyme-pattern/detect", json={**body, "canvas_id": their_canvas})
    assert res.status_code == 404
    # malformed ids are not found, not server errors
    res = await me.post("/api/nlp/rhyme-pattern/detect", json={**body, "snippet_id": "nope"})
    assert res.status_code == 404
