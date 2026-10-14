import uuid
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.services import rhyme_suggest_service, snippet_service

from .fakes import FakeDictionary, default_repo

USER_ID = "u1"


@pytest.fixture
def client(monkeypatch):
    repo = default_repo()
    monkeypatch.setattr(rhyme_suggest_service, "DbSnippetRepository", lambda db: repo)
    monkeypatch.setattr(rhyme_suggest_service, "default_dictionary", FakeDictionary)

    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER_ID)
    # no context manager: the lifespan would try to reach Postgres
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_rhyme_suggest_ok(client: TestClient):
    res = client.post(
        "/api/nlp/rhyme-suggest",
        json={"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}], "k": 1, "limit": 5},
    )
    assert res.status_code == 200, res.text
    body = res.json()
    assert set(body) == {"anchors", "candidates", "total", "elapsed_ms"}
    assert len(body["candidates"]) == 5
    assert body["total"] >= 5
    cand = body["candidates"][0]
    for key in (
        "id", "text", "provenance", "ephemeral", "syllable_count", "stress", "rhyme_key", "syllables", "grid_line",
        "tags", "score", "match_depth", "best_anchor_id", "fits_slot", "alignment", "source",
    ):
        assert key in cand
    assert set(cand["alignment"][0]) == {"cand_index", "anchor_index", "nucleus", "coda", "onset", "total"}
    assert set(cand["syllables"][0]) == {"onset", "nucleus", "coda", "stress"}
    assert set(cand["grid_line"]) == {"cells", "syllable_count", "raw_text"}
    anchor = body["anchors"][0]
    assert anchor["provenance"] == "generated" and anchor["text"] == "cat"


def test_rhyme_suggest_range_anchor_is_echoed_with_its_grid_slice(client: TestClient):
    res = client.post(
        "/api/nlp/rhyme-suggest",
        json={
            "canvas_id": "c1",
            "anchors": [{"kind": "snippet", "snippet_id": "s-cat", "line_index": 0, "word_start": 3, "word_end": 5}],
            "k": 1,
            "limit": 5,
        },
    )
    assert res.status_code == 200, res.text
    anchor = res.json()["anchors"][0]
    assert anchor["id"] == "note:s-cat:0:3-5"
    assert anchor["text"] == "black cat"
    assert anchor["word_offset"] == 3 and anchor["word_end"] == 5
    assert anchor["grid_line"]["raw_text"] == "black cat"
    assert [c["word_index"] for c in anchor["grid_line"]["cells"]] == [0, 1]


def test_rhyme_suggest_404_for_foreign_canvas_and_snippet(client: TestClient):
    res = client.post("/api/nlp/rhyme-suggest", json={"canvas_id": "c-other", "anchors": [{"kind": "text", "text": "cat"}]})
    assert res.status_code == 404
    res = client.post(
        "/api/nlp/rhyme-suggest", json={"canvas_id": "c1", "anchors": [{"kind": "snippet", "snippet_id": "s-other"}]}
    )
    assert res.status_code == 404


@pytest.mark.parametrize(
    "body",
    [
        {"canvas_id": "c1", "anchors": []},
        {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}], "k": 9},
        {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}], "slot": {"syllable_count": 2, "stress": "1a"}},
        {"canvas_id": "c1", "anchors": [{"kind": "snippet", "snippet_id": "s-cat", "line_index": 9}]},
        {"canvas_id": "c1", "anchors": [{"kind": "snippet", "snippet_id": "s-cat", "line_index": 0, "word_start": 9}]},
        {"canvas_id": "c1", "anchors": [{"kind": "snippet", "snippet_id": "s-cat", "line_index": 0, "word_start": 2, "word_end": 1}]},
        {"canvas_id": "c1", "anchors": [{"kind": "snippet", "snippet_id": "s-cat", "line_index": 0, "word_start": -1}]},
        {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "42"}]},
        {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}], "dialect": {"rules": [{"kind": "merge", "source": ["XX"], "target": ["AA"]}]}},
    ],
)
def test_rhyme_suggest_422(client: TestClient, body):
    res = client.post("/api/nlp/rhyme-suggest", json=body)
    assert res.status_code == 422, res.text


def test_create_snippet_computes_phonetics_and_keeps_derived_from(client: TestClient, monkeypatch):
    captured = {}

    async def fake_create(db, canvas_id, owner_id, **kwargs):
        captured.update(kwargs)
        return SimpleNamespace(
            id=uuid.uuid4(),
            canvas_id=uuid.UUID(canvas_id),
            snippet_type=SimpleNamespace(value="freestyle"),
            grid_mode=SimpleNamespace(value="auto"),
            beats_per_line=None,
            grid_data=kwargs["grid_data"],
            raw_text=kwargs["raw_text"],
            position_x=1.0,
            position_y=2.0,
            width=kwargs["width"],
            height=kwargs["height"],
            z_index=0,
            group_id=None,
            tags=[],
            phonetics=kwargs["phonetics"],
            derived_from=kwargs["derived_from"],
            created_at="now",
            updated_at="now",
            content_updated_at="now",
        )

    monkeypatch.setattr(snippet_service, "create_snippet", fake_create)
    derived = {
        "provenance": "dictionary",
        "source_unit_id": "dict:bat",
        "score": 1.0,
        "match_depth": 1,
        "k": 1,
        "anchor_unit_id": "text:abc",
        "anchor_text": "cat",
    }
    res = client.post(
        f"/api/canvases/{uuid.uuid4()}/snippets",
        json={"raw_text": "bat", "position_x": 1, "position_y": 2, "derived_from": derived},
    )
    assert res.status_code == 201, res.text
    body = res.json()
    assert captured["derived_from"] == derived
    assert captured["phonetics"]["lines"][0]["stress"] == "1"
    assert captured["phonetics"]["lines"][0]["rhyme_key"] == ["AE", "T"]
    assert body["derived_from"] == derived
    assert body["phonetics"]["lines"][0]["rhyme_key"] == ["AE", "T"]


def test_legacy_rhymes_route_shape(client: TestClient, monkeypatch):
    from sqlalchemy.ext.asyncio import AsyncSession

    class FakeResult:
        def scalars(self):
            return self

        def all(self):
            return [SimpleNamespace(id="s1", raw_text="wearing my hat"), SimpleNamespace(id="s2", raw_text="the dog")]

    class FakeSession:
        async def execute(self, *_args, **_kwargs):
            return FakeResult()

    async def fake_db():
        yield FakeSession()

    app.dependency_overrides[get_db] = fake_db
    res = client.get("/api/nlp/rhymes", params={"word": "cat"})
    assert res.status_code == 200, res.text
    body = res.json()
    assert set(body) == {"dictionary", "snippets"}
    assert "bat" in body["dictionary"]["perfect"]
    assert body["snippets"] == [
        {"word": "hat", "rhyme_type": "perfect", "source": "snippet", "snippet_id": "s1", "snippet_preview": "wearing my hat"}
    ]


def test_create_snippet_passes_enum_types_to_service(client: TestClient, monkeypatch):
    """Sessions use expire_on_commit=False, so the ORM object keeps whatever the
    route passed in; a json-mode dump would leak plain strings into the response."""
    from app.models.snippet import GridMode, SnippetType

    captured = {}

    async def fake_create(db, canvas_id, owner_id, **kwargs):
        captured.update(kwargs)
        return SimpleNamespace(
            id=uuid.uuid4(),
            canvas_id=uuid.UUID(canvas_id),
            snippet_type=kwargs["snippet_type"],
            grid_mode=kwargs["grid_mode"],
            beats_per_line=None,
            grid_data=kwargs["grid_data"],
            raw_text=kwargs["raw_text"],
            position_x=0.0,
            position_y=0.0,
            width=kwargs["width"],
            height=kwargs["height"],
            z_index=0,
            group_id=None,
            tags=[],
            phonetics=kwargs["phonetics"],
            derived_from=None,
            created_at="now",
            updated_at="now",
            content_updated_at="now",
        )

    monkeypatch.setattr(snippet_service, "create_snippet", fake_create)
    res = client.post(
        f"/api/canvases/{uuid.uuid4()}/snippets",
        json={"raw_text": "bat", "snippet_type": "verse"},
    )
    assert res.status_code == 201, res.text
    assert captured["snippet_type"] is SnippetType.verse
    assert captured["grid_mode"] is GridMode.auto
    assert res.json()["snippet_type"] == "verse"
