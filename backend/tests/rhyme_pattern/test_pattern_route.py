"""POST /api/nlp/rhyme-pattern/{detect,suggest} through the app (fake repository and dictionary)."""

from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.services import rhyme_pattern_service

from .fakes import fake_dictionary_index, pattern_repo

DETECT = "/api/nlp/rhyme-pattern/detect"
SUGGEST = "/api/nlp/rhyme-pattern/suggest"
RANGE = {"canvas_id": "c1", "snippet_id": "s-verse", "line_start": 0, "line_end": 3}


def _span(line: int, start: int, end: int | None = None) -> dict:
    return {"line_index": line, "word_start": start, "word_end": start + 1 if end is None else end}


SLOTS = [
    {"id": "s0", "members": [_span(0, 3), _span(1, 0), _span(2, 0)]},
    {"id": "s1", "members": [_span(0, 6), _span(1, 4), _span(2, 4)]},
    {"id": "s2", "members": [_span(0, 7), _span(1, 5), _span(2, 5)]},
]


@pytest.fixture
def client(monkeypatch):
    repo = pattern_repo()
    monkeypatch.setattr(rhyme_pattern_service, "DbPatternRepository", lambda db: repo)
    monkeypatch.setattr(rhyme_pattern_service, "default_dictionary_index", fake_dictionary_index)

    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id="u1")
    yield TestClient(app)
    app.dependency_overrides.clear()


def _error(res, code: str) -> None:
    assert res.status_code == 422, res.text
    detail = res.json()["detail"]
    assert detail["error"] == code
    assert isinstance(detail["message"], str) and detail["message"]


def test_detect_shape(client: TestClient):
    res = client.post(DETECT, json=RANGE)
    assert res.status_code == 200, res.text
    body = res.json()
    assert set(body) == {"lines", "slots", "elapsed_ms"}
    line = body["lines"][0]
    assert set(line) == {"line_index", "text", "words", "grid_line"}
    assert set(line["words"][0]) == {"word_index", "text", "syllable_count", "stress"}
    assert set(line["grid_line"]) == {"cells", "syllable_count", "raw_text"}
    slot = body["slots"][0]
    assert set(slot) == {"id", "members", "coverage", "full", "default_on", "k", "cohesion", "syllable_count", "stress"}
    assert set(slot["members"][0]) == {
        "line_index", "word_start", "word_end", "text", "syllable_count", "stress", "rhyme_key", "syllables",
    }
    assert [s["id"] for s in body["slots"]] == ["s0", "s1", "s2"]


def test_suggest_shape(client: TestClient):
    res = client.post(SUGGEST, json={**RANGE, "slots": SLOTS, "limit": 5})
    assert res.status_code == 200, res.text
    body = res.json()
    assert set(body) == {"slots", "line_matches", "line_match_total", "elapsed_ms"}
    result = body["slots"][0]
    assert set(result) == {"slot", "candidates", "total"}
    assert result["slot"]["id"] == "s0"
    assert 0 < len(result["candidates"]) <= 5
    cand = result["candidates"][0]
    for key in (
        "id", "text", "provenance", "ephemeral", "syllable_count", "stress", "rhyme_key", "syllables", "grid_line",
        "tags", "score", "match_depth", "best_anchor_id", "fits_slot", "alignment", "source", "member_scores",
    ):
        assert key in cand
    assert cand["best_anchor_id"].startswith("pattern:s-verse:")
    match = body["line_matches"][0]
    assert set(match) == {"unit", "hits", "slots_hit", "score"}
    assert set(match["hits"][0]) == {"slot_id", "word_start", "word_end", "text", "score"}
    assert match["unit"]["id"].startswith("note:")


def test_not_found(client: TestClient):
    assert client.post(DETECT, json={**RANGE, "canvas_id": "c-other"}).status_code == 404
    assert client.post(DETECT, json={**RANGE, "snippet_id": "s-elsewhere"}).status_code == 404
    assert client.post(SUGGEST, json={**RANGE, "snippet_id": "s-foreign", "slots": SLOTS}).status_code == 404


def test_pattern_error_bodies(client: TestClient):
    _error(client.post(DETECT, json={**RANGE, "line_end": 1}), "pattern_range_invalid")
    _error(client.post(DETECT, json={**RANGE, "line_end": 40}), "pattern_range_invalid")
    _error(client.post(SUGGEST, json={**RANGE, "slots": [{"members": [_span(5, 0)]}]}), "pattern_span_invalid")
    _error(client.post(SUGGEST, json={**RANGE, "slots": [{"members": [_span(0, 3, 3)]}]}), "pattern_span_invalid")
    _error(client.post(SUGGEST, json={**RANGE, "slots": [SLOTS[2], SLOTS[0]]}), "pattern_slot_order")


def test_invalid_tag_filter(client: TestClient):
    res = client.post(SUGGEST, json={**RANGE, "slots": SLOTS, "tag_filter": "hook &"})
    _error(res, "invalid_tag_expression")
    assert "expression_error" in res.json()["detail"]


@pytest.mark.parametrize(
    ("path", "body"),
    [
        (DETECT, {**RANGE, "min_score": 0.2}),
        (DETECT, {**RANGE, "line_start": -1}),
        (DETECT, {**RANGE, "snippet_id": ""}),
        (DETECT, {**RANGE, "dialect": {"rules": [{"kind": "merge", "source": ["XX"], "target": ["AA"]}]}}),
        (SUGGEST, {**RANGE, "slots": []}),
        (SUGGEST, {**RANGE, "slots": SLOTS * 3}),
        (SUGGEST, {**RANGE, "slots": [{"members": []}]}),
        (SUGGEST, {**RANGE, "slots": [{"id": "a", "members": [_span(0, 3)]}, {"id": "a", "members": [_span(0, 6)]}]}),
        (SUGGEST, {**RANGE, "slots": SLOTS, "limit": 0}),
        (SUGGEST, {**RANGE, "slots": SLOTS, "limit": 101}),
        (SUGGEST, {**RANGE, "slots": SLOTS, "offsets": {"s0": -1}}),
        (SUGGEST, {**RANGE, "slots": [{"k": 5, "members": [_span(0, 3)]}]}),
        (SUGGEST, {**RANGE, "slots": SLOTS, "sources": []}),
    ],
)
def test_validation_422(client: TestClient, path, body):
    assert client.post(path, json=body).status_code == 422
