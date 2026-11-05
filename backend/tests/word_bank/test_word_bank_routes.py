"""Word bank routes without a database: validation, error bodies, related-word errors.

Everything here fails (or answers) before the service touches the DB; tests/word_bank/
test_word_bank_api_db.py covers the routes end to end against PostgreSQL.
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.api.word_bank import get_related_words
from app.db.database import get_db
from app.main import app
from app.services.word_bank import service
from app.services.word_bank.errors import RelatedWordsUnavailableError, RelatedWordsWarmingError
from app.services.word_bank.related import RelatedHit

USER_ID = uuid.uuid4()


class FakeRelated:
    def __init__(self, error: Exception | None = None, hits: list[RelatedHit] | None = None) -> None:
        self.error = error
        self.hits = hits or []
        self.seen: list[tuple[str, int]] = []

    async def search(self, query: str, limit: int) -> list[RelatedHit]:
        self.seen.append((query, limit))
        if self.error is not None:
            raise self.error
        return self.hits[:limit]


@pytest.fixture
def related():
    return FakeRelated()


@pytest.fixture
def client(related, monkeypatch):
    async def fake_db():
        yield object()

    async def no_bank(db, owner, texts):
        return {}

    monkeypatch.setattr(service, "ids_by_lower_text", no_bank)
    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER_ID)
    app.dependency_overrides[get_related_words] = lambda: related
    yield TestClient(app)
    app.dependency_overrides.clear()


def assert_error(res, status: int, code: str) -> dict:
    assert res.status_code == status, res.text
    detail = res.json()["detail"]
    assert detail["error"] == code
    assert isinstance(detail["message"], str) and detail["message"]
    return detail


def test_routes_need_auth():
    anonymous = TestClient(app)
    for method, path, body in [
        ("get", "/api/word-bank", None),
        ("post", "/api/word-bank", {"words": ["a"]}),
        ("put", f"/api/word-bank/{uuid.uuid4()}", {"text": "a"}),
        ("post", "/api/word-bank/delete", {"ids": ["x"]}),
        ("post", "/api/word-bank/tags", {"word_ids": ["x"], "tag_id": "y", "op": "link"}),
        ("post", "/api/word-bank/rhymes", {"text": "a"}),
        ("post", "/api/word-bank/families", {}),
        ("get", "/api/word-bank/related?q=night", None),
    ]:
        res = anonymous.request(method, path, json=body)
        assert res.status_code in (401, 403), (method, path, res.status_code)


# ---- add ----


def test_add_invalid_word_reports_first_bad_index(client):
    res = client.post("/api/word-bank", json={"words": ["fine", "also fine", "bad1", "!"]})
    detail = assert_error(res, 422, "invalid_word")
    assert detail["index"] == 2


def test_add_too_many_words_and_tags(client):
    assert_error(client.post("/api/word-bank", json={"words": ["a"] * 201}), 422, "too_many_words")
    tags = [str(uuid.uuid4()) for _ in range(21)]
    assert_error(client.post("/api/word-bank", json={"words": ["a"], "tag_ids": tags}), 422, "too_many_words")


def test_add_empty_batch_is_a_validation_error(client):
    assert client.post("/api/word-bank", json={"words": []}).status_code == 422


def test_add_malformed_tag_id_is_tag_not_found(client):
    assert_error(client.post("/api/word-bank", json={"words": ["a"], "tag_ids": ["nope"]}), 404, "tag_not_found")


# ---- rename ----


def test_rename_invalid_word_has_no_index(client):
    detail = assert_error(client.put(f"/api/word-bank/{uuid.uuid4()}", json={"text": "x" * 65}), 422, "invalid_word")
    assert "index" not in detail


# ---- delete / tags limits ----


def test_delete_and_tags_limits(client):
    assert_error(client.post("/api/word-bank/delete", json={"ids": ["x"] * 501}), 422, "too_many_words")
    assert client.post("/api/word-bank/delete", json={"ids": []}).status_code == 422
    body = {"word_ids": ["x"] * 501, "tag_id": str(uuid.uuid4()), "op": "link"}
    assert_error(client.post("/api/word-bank/tags", json=body), 422, "too_many_words")
    assert client.post("/api/word-bank/tags", json={**body, "word_ids": ["x"], "op": "toggle"}).status_code == 422


def test_tags_malformed_ids(client):
    body = {"word_ids": [str(uuid.uuid4())], "tag_id": "nope", "op": "link"}
    assert_error(client.post("/api/word-bank/tags", json=body), 404, "tag_not_found")


# ---- rhymes / families validation ----


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"word_id": str(uuid.uuid4()), "text": "night"},
        {"text": "night", "min_score": 1.5},
        {"text": "night", "k": 0},
        {"text": "night", "k": 5},
        {"text": "night", "limit": 0},
        {"text": "night", "limit": 201},
    ],
)
def test_rhymes_request_validation(client, body):
    assert client.post("/api/word-bank/rhymes", json=body).status_code == 422


def test_rhymes_invalid_tag_filter(client):
    res = client.post("/api/word-bank/rhymes", json={"text": "night", "tag_filter": "love &"})
    detail = assert_error(res, 422, "invalid_tag_expression")
    assert set(detail["expression_error"]) == {"code", "position", "message"}


def test_rhymes_invalid_text(client):
    assert_error(client.post("/api/word-bank/rhymes", json={"text": "123"}), 422, "invalid_word")


def test_rhymes_malformed_word_id(client):
    assert_error(client.post("/api/word-bank/rhymes", json={"word_id": "nope"}), 404, "word_not_found")


def test_families_validation(client):
    assert client.post("/api/word-bank/families", json={"min_score": -0.1}).status_code == 422
    assert client.post("/api/word-bank/families", json={"k": 5}).status_code == 422
    detail = assert_error(client.post("/api/word-bank/families", json={"tag_filter": "("}), 422, "invalid_tag_expression")
    assert "expression_error" in detail


# ---- related ----


def test_related_results(client, related):
    related.hits = [RelatedHit("dark", 0.81), RelatedHit("moon", 0.5)]
    res = client.get("/api/word-bank/related", params={"q": "  night  ", "limit": 5})
    assert res.status_code == 200
    assert res.json() == {
        "query": "night",
        "results": [
            {"text": "dark", "similarity": 0.81, "bank_word_id": None},
            {"text": "moon", "similarity": 0.5, "bank_word_id": None},
        ],
    }
    assert related.seen == [("night", 5)]


def test_related_default_limit(client, related):
    client.get("/api/word-bank/related", params={"q": "night"})
    assert related.seen == [("night", 30)]


def test_related_warming_sets_retry_after(client, related):
    related.error = RelatedWordsWarmingError("building")
    res = client.get("/api/word-bank/related", params={"q": "night"})
    assert_error(res, 503, "related_words_warming")
    assert res.headers["retry-after"] == "10"


def test_related_unavailable(client, related):
    related.error = RelatedWordsUnavailableError("no model")
    res = client.get("/api/word-bank/related", params={"q": "night"})
    assert_error(res, 503, "related_words_unavailable")
    assert "retry-after" not in res.headers


@pytest.mark.parametrize("q", ["", "   ", "x" * 65])
def test_related_invalid_query(client, related, q):
    assert_error(client.get("/api/word-bank/related", params={"q": q}), 422, "invalid_word")
    assert related.seen == []


@pytest.mark.parametrize("limit", [0, 101])
def test_related_limit_range(client, limit):
    assert client.get("/api/word-bank/related", params={"q": "night", "limit": limit}).status_code == 422


def test_related_query_may_be_a_theme(client, related):
    res = client.get("/api/word-bank/related", params={"q": "late night, city lights 2am"})
    assert res.status_code == 200
    assert related.seen == [("late night, city lights 2am", 30)]
