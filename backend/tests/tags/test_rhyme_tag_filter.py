"""``tag_filter`` on rhyme-suggest restricts the notes source to matching snippets."""

from __future__ import annotations

from dataclasses import replace
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.models.snippet import SnippetType
from app.schemas.rhyme import RhymeSuggestRequest
from app.services import rhyme_suggest_service
from app.services.rhyme_suggest_service import suggest
from app.services.tag_expr import TagExpressionError
from tests.fakes import FakeDictionary, FakeRepository, make_record


def repo() -> FakeRepository:
    r = FakeRepository(canvases={"c1": "u1", "c2": "u1", "c-other": "u2"})
    r.snippets = {
        "s-hat": replace(make_record("s-hat", "wearing my hat", tags=("t-love",)), snippet_type=SnippetType.verse, tag_names=("Love",)),
        "s-bat": replace(make_record("s-bat", "swinging a bat", tags=("t-night",)), snippet_type=SnippetType.hook, tag_names=("night",)),
        "s-mat": replace(make_record("s-mat", "on the mat", canvas_id="c2"), snippet_type=SnippetType.chorus),
        "s-cat": replace(make_record("s-cat", "a black cat", tags=("t-love",)), snippet_type=SnippetType.verse, tag_names=("Love",)),
    }
    return r


def req(**patch) -> RhymeSuggestRequest:
    base = {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}], "k": 1, "limit": 100}
    return RhymeSuggestRequest.model_validate({**base, **patch})


async def note_snippets(**patch) -> set[str]:
    res = await suggest(req(**patch), "u1", repo(), dictionary=FakeDictionary)
    return {c.snippet_id for c in res.candidates if c.source == "notes"}


async def dictionary_ids(**patch) -> set[str]:
    res = await suggest(req(**patch), "u1", repo(), dictionary=FakeDictionary)
    return {c.id for c in res.candidates if c.source == "dictionary"}


def test_record_defaults_keep_old_constructors_working():
    record = make_record("s", "x")
    assert record.snippet_type is SnippetType.freestyle
    assert record.tag_names == ()


async def test_no_filter_uses_every_note():
    assert await note_snippets() == {"s-hat", "s-bat", "s-mat", "s-cat"}
    assert await note_snippets(tag_filter=None) == await note_snippets(tag_filter="   ")


@pytest.mark.parametrize(
    ("expression", "want"),
    [
        ("love", {"s-hat", "s-cat"}),
        ("LOVE", {"s-hat", "s-cat"}),
        ("!love", {"s-bat", "s-mat"}),
        ("type:hook", {"s-bat"}),
        ("type:chorus | night", {"s-bat", "s-mat"}),
        ("ghost", set()),
    ],
)
async def test_filter_restricts_notes(expression, want):
    assert await note_snippets(tag_filter=expression) == want


async def test_dictionary_is_never_filtered():
    assert await dictionary_ids(tag_filter="ghost") == await dictionary_ids()


async def test_filter_does_not_touch_snippet_anchors():
    res = await suggest(
        req(anchors=[{"kind": "snippet", "snippet_id": "s-cat"}], tag_filter="type:hook"), "u1", repo(), dictionary=FakeDictionary
    )
    assert res.anchors[0].snippet_id == "s-cat"
    assert {c.snippet_id for c in res.candidates if c.source == "notes"} == {"s-bat"}


async def test_invalid_filter_raises_before_anything_else():
    with pytest.raises(TagExpressionError):
        await suggest(req(canvas_id="c-other", tag_filter="love &"), "u1", repo(), dictionary=FakeDictionary)


@pytest.fixture
def client(monkeypatch):
    r = repo()
    monkeypatch.setattr(rhyme_suggest_service, "DbSnippetRepository", lambda db: r)
    monkeypatch.setattr(rhyme_suggest_service, "default_dictionary", FakeDictionary)

    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id="u1")
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_route_filters_and_rejects_invalid_with_expression_error(client):
    body = {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}], "k": 1, "limit": 100}
    ok = client.post("/api/nlp/rhyme-suggest", json={**body, "tag_filter": "type:hook"})
    assert ok.status_code == 200, ok.text
    assert {c["snippet_id"] for c in ok.json()["candidates"] if c["source"] == "notes"} == {"s-bat"}

    bad = client.post("/api/nlp/rhyme-suggest", json={**body, "tag_filter": "type:solo"})
    assert bad.status_code == 422
    detail = bad.json()["detail"]
    assert detail["error"] == "invalid_tag_expression"
    assert detail["message"]
    assert detail["expression_error"]["code"] == "unknown_type"
    assert detail["expression_error"]["position"] == 0
