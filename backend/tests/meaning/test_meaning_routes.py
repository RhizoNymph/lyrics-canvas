"""The three rhyme endpoints through the app with order=meaning (fake data, fake embedder)."""

import uuid
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.api.embeddings import get_meaning_service, meaning_service as production_meaning
from app.db.database import get_db
from app.main import app
from app.services import rhyme_pattern_service, rhyme_suggest_service
from app.services.meaning import store as meaning_store
from app.services.rhyme_order import BAND_RANK
from app.services.word_bank import service as bank_service
from tests.fakes import FakeDictionary, default_repo
from tests.rhyme_pattern.fakes import fake_dictionary_index, pattern_repo

from .fakes import FakeStore, meaning_service

USER = uuid.uuid4()
WORDS = ("bat", "hat", "rat", "mat", "flat", "night", "dog")


def _bank_word(text: str) -> SimpleNamespace:
    return SimpleNamespace(id=uuid.uuid4(), text=text, tags=[], created_at=datetime(2026, 11, 1, tzinfo=timezone.utc), version=1)


@pytest.fixture
def meaning():
    service, _ = meaning_service()
    return service


@pytest.fixture
def client(monkeypatch, meaning):
    monkeypatch.setattr(rhyme_suggest_service, "DbSnippetRepository", lambda db: default_repo())
    monkeypatch.setattr(rhyme_suggest_service, "default_dictionary", FakeDictionary)
    monkeypatch.setattr(rhyme_pattern_service, "DbPatternRepository", lambda db: pattern_repo())
    monkeypatch.setattr(rhyme_pattern_service, "default_dictionary_index", fake_dictionary_index)
    import app.api.nlp as nlp_api

    monkeypatch.setattr(nlp_api, "DbSnippetEmbeddingStore", lambda db: FakeStore())
    bank = [_bank_word(w) for w in WORDS]

    async def filtered_words(db, owner, tag_filter):
        return bank

    async def get_word(db, owner, word_id):
        return next(w for w in bank if w.id == word_id)

    monkeypatch.setattr(bank_service, "filtered_words", filtered_words)
    monkeypatch.setattr(bank_service, "get_word", get_word)

    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER)
    app.dependency_overrides[get_meaning_service] = lambda: meaning
    client = TestClient(app)
    client.bank = bank  # type: ignore[attr-defined]
    yield client
    app.dependency_overrides.clear()


def _assert_banded(rows, score_key="score"):
    ranks = [BAND_RANK[r["band"]] for r in rows]
    assert ranks == sorted(ranks)
    for a, b in zip(rows, rows[1:]):
        if a["band"] == b["band"]:
            assert a["similarity"] >= b["similarity"]


def test_production_dependency_is_the_process_service():
    assert get_meaning_service() is production_meaning
    assert meaning_store.DbSnippetEmbeddingStore is not None


def test_rhyme_suggest_meaning(client, monkeypatch):
    # the suggest service owns the user id check; route through user "u1" of the fake repo
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id="u1")
    body = {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}], "k": 1, "limit": 20, "order": "meaning"}
    res = client.post("/api/nlp/rhyme-suggest", json=body)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["order_applied"] == "meaning"
    assert all("band" in c and isinstance(c["similarity"], float) for c in data["candidates"])
    _assert_banded(data["candidates"])

    res = client.post("/api/nlp/rhyme-suggest", json={**body, "order": "rhyme"})
    data = res.json()
    assert data["order_applied"] == "rhyme"
    assert all(c["similarity"] is None and c["band"] in BAND_RANK for c in data["candidates"])


def test_pattern_suggest_meaning(client):
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id="u1")
    span = lambda line, start: {"line_index": line, "word_start": start, "word_end": start + 1}  # noqa: E731
    body = {
        "canvas_id": "c1", "snippet_id": "s-verse", "line_start": 0, "line_end": 3,
        "slots": [{"id": "s0", "members": [span(0, 3), span(1, 0), span(2, 0)]}], "order": "meaning",
    }
    res = client.post("/api/nlp/rhyme-pattern/suggest", json=body)
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["order_applied"] == "meaning"
    candidates = data["slots"][0]["candidates"]
    assert candidates and all(isinstance(c["similarity"], float) for c in candidates)
    _assert_banded(candidates)


def test_word_bank_rhymes_meaning_sorts_before_the_limit(client):
    res = client.post("/api/word-bank/rhymes", json={"text": "cat", "k": 1, "order": "meaning", "limit": 2})
    assert res.status_code == 200, res.text
    data = res.json()
    assert data["order_applied"] == "meaning"
    # bat and rat (animals) outrank hat, mat, flat in the perfect band, though rhyme order puts them by text
    assert sorted(m["word"]["text"] for m in data["matches"]) == ["bat", "rat"]
    assert all(m["band"] == "perfect" and isinstance(m["similarity"], float) for m in data["matches"])

    res = client.post("/api/word-bank/rhymes", json={"text": "cat", "k": 1, "limit": 2})
    data = res.json()
    assert data["order_applied"] == "rhyme"
    assert [m["word"]["text"] for m in data["matches"]] == ["bat", "flat"]
    assert all(m["similarity"] is None and m["band"] == "perfect" for m in data["matches"])


def test_word_bank_rhymes_meaning_with_a_word_anchor(client):
    anchor = client.bank[0]  # bat
    res = client.post("/api/word-bank/rhymes", json={"word_id": str(anchor.id), "k": 1, "order": "meaning"})
    assert res.status_code == 200, res.text
    data = res.json()
    texts = [m["word"]["text"] for m in data["matches"]]
    assert "bat" not in texts and texts[0] == "rat"
    _assert_banded(data["matches"])


def test_word_bank_rhymes_unavailable_model_falls_back(client):
    unavailable, _ = meaning_service(unavailable=True)
    app.dependency_overrides[get_meaning_service] = lambda: unavailable
    res = client.post("/api/word-bank/rhymes", json={"text": "cat", "k": 1, "order": "meaning", "limit": 2})
    data = res.json()
    assert data["order_applied"] == "rhyme"
    assert [m["word"]["text"] for m in data["matches"]] == ["bat", "flat"]


@pytest.mark.parametrize(
    "path,body",
    [
        ("/api/nlp/rhyme-suggest", {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}]}),
        ("/api/word-bank/rhymes", {"text": "cat"}),
        (
            "/api/nlp/rhyme-pattern/suggest",
            {"canvas_id": "c1", "snippet_id": "s-verse", "line_start": 0, "line_end": 3,
             "slots": [{"members": [{"line_index": 0, "word_start": 3, "word_end": 4}]}]},
        ),
    ],
)
def test_unknown_order_is_rejected(client, path, body):
    res = client.post(path, json={**body, "order": "alphabetical"})
    assert res.status_code == 422
