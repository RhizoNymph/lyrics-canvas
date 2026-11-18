"""rhyme-suggest rhythm_fit end to end over HTTP against Postgres (TAG_TEST_DATABASE_URL, see
tests/tags/db.py): the anchor's score is read from snippets.score by ``DbSnippetRepository``."""

from __future__ import annotations

import uuid

import httpx
import pytest
from sqlalchemy import update

from app.models.snippet import Snippet
from app.services import rhyme_suggest_service
from tests.fakes import FakeDictionary
from tests.placements.conftest import db, migrated  # noqa: F401  (fixtures)
from tests.placements.db import make_user
from tests.score.helpers import score
from tests.search.conftest import api  # noqa: F401  (fixture)
from tests.tags.db import requires_db

pytestmark = requires_db

WORDS = ("delight", "despite", "midnight", "daylight", "night", "light", "overnight")
TONIGHT = score([[(0, 96, 24, 0), (1, 144, 24, 1), (2, 168, 24, 0), (3, 192, 48, 1)]])


@pytest.fixture(autouse=True)
def fake_dictionary(monkeypatch):
    monkeypatch.setattr(
        rhyme_suggest_service, "default_dictionary", lambda d, w, r: FakeDictionary(d, w, r, words=WORDS)
    )


async def _canvas(client: httpx.AsyncClient, title: str) -> str:
    res = await client.post("/api/canvases", json={"title": title})
    assert res.status_code == 201, res.text
    return res.json()["id"]


async def _snippet(client: httpx.AsyncClient, canvas_id: str, text: str) -> str:
    res = await client.post(f"/api/canvases/{canvas_id}/snippets", json={"raw_text": text, "position_x": 0, "position_y": 0})
    assert res.status_code == 201, res.text
    return res.json()["id"]


async def test_rhythm_fit_reads_the_stored_score(db, api):  # noqa: F811
    owner = await make_user(db)
    await db.commit()
    me = api(owner)
    canvas = await _canvas(me, "Song")
    scored = await _snippet(me, canvas, "we dance tonight")
    plain = await _snippet(me, canvas, "we dance tonight")
    await db.execute(update(Snippet).where(Snippet.id == uuid.UUID(scored)).values(score=TONIGHT))
    await db.commit()

    def body(snippet_id: str, **patch):
        anchor = {"kind": "snippet", "snippet_id": snippet_id, "line_index": 0, "word_start": 2, "word_end": 3}
        return {"canvas_id": canvas, "anchors": [anchor], "k": 1, "sources": ["dictionary"], "rhythm_fit": True, **patch}

    res = await me.post("/api/nlp/rhyme-suggest", json=body(scored))
    assert res.status_code == 200, res.text
    out = res.json()
    assert out["rhythm_applied"] is True and out["rhythm_unavailable_reason"] is None
    fits = {c["text"]: c["rhythm_fit"] for c in out["candidates"]}
    assert fits == {"delight": 1.0, "despite": 1.0, "midnight": 0.25, "daylight": 0.25}
    assert out["total"] == 4
    assert [c["text"] for c in out["candidates"]][:2] in (["delight", "despite"], ["despite", "delight"])

    res = await me.post("/api/nlp/rhyme-suggest", json=body(plain))
    out = res.json()
    assert out["rhythm_applied"] is False and out["rhythm_unavailable_reason"] == "no_score"
    assert all(c["rhythm_fit"] is None for c in out["candidates"])
    assert {"night", "light", "overnight"} <= {c["text"] for c in out["candidates"]}

    res = await me.post("/api/nlp/rhyme-suggest", json=body(scored, rhythm_fit=False))
    out = res.json()
    assert out["rhythm_applied"] is False and out["rhythm_unavailable_reason"] is None
    assert all(c["rhythm_fit"] is None for c in out["candidates"])
