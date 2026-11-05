"""Word bank HTTP routes end to end against a real, throwaway PostgreSQL database.

See tests/tags/db.py for how to point this at a throwaway database.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import datetime
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.api.deps import get_current_user
from app.api.word_bank import get_related_words
from app.db.database import get_db
from app.main import app
from app.models.user import User
from app.services.word_bank.related import RelatedHit
from tests.tags.db import DB_URL, alembic, requires_db

pytestmark = requires_db


@pytest.fixture(scope="module", autouse=True)
def migrated():
    alembic("downgrade", "base")
    alembic("upgrade", "head")
    yield
    alembic("downgrade", "base")


async def _session():
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)()
    try:
        yield session
    finally:
        await session.close()
        await engine.dispose()


async def _new_user() -> uuid.UUID:
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    async with async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)() as db:
        user = User(email=f"{uuid.uuid4()}@example.test", display_name="t")
        db.add(user)
        await db.commit()
        user_id = user.id
    await engine.dispose()
    return user_id


class FakeRelated:
    def __init__(self) -> None:
        self.hits: list[RelatedHit] = []

    async def search(self, query: str, limit: int) -> list[RelatedHit]:
        return self.hits[:limit]


@pytest.fixture
def related():
    return FakeRelated()


class Users:
    """Switch the authenticated user between tests' users."""

    def __init__(self) -> None:
        self.current: uuid.UUID | None = None

    def new(self) -> uuid.UUID:
        self.current = asyncio.run(_new_user())
        return self.current


@pytest.fixture
def users():
    return Users()


@pytest.fixture
def client(users, related):
    app.dependency_overrides[get_db] = _session
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=users.current)
    app.dependency_overrides[get_related_words] = lambda: related
    users.new()
    yield TestClient(app)
    app.dependency_overrides.clear()


def ok(res, status: int = 200):
    assert res.status_code == status, res.text
    return res.json() if res.content else None


def assert_error(res, status: int, code: str) -> dict:
    assert res.status_code == status, res.text
    detail = res.json()["detail"]
    assert detail["error"] == code and detail["message"]
    return detail


def add(client, *words, tag_ids=()):
    return ok(client.post("/api/word-bank", json={"words": list(words), "tag_ids": list(tag_ids)}), 201)


def tag(client, name):
    return ok(client.post("/api/tags", json={"name": name}), 201)["id"]


def texts(words):
    return [w["text"] for w in words]


def by_text(client):
    return {w["text"]: w for w in ok(client.get("/api/word-bank"))}


# ---- list / add ----


def test_add_and_list(client):
    love = tag(client, "love")
    result = add(client, "night", " Broken   Heart ", "NIGHT", tag_ids=[love])
    assert texts(result["created"]) == ["night", "Broken Heart"]
    assert result["existing"] == []
    word = result["created"][0]
    assert set(word) == {"id", "text", "tags", "created_at"}
    assert word["tags"] == [{"id": love, "name": "love", "color": "#6366f1"}]
    datetime.fromisoformat(word["created_at"])
    again = add(client, "Night", "day")
    assert texts(again["created"]) == ["day"]
    assert texts(again["existing"]) == ["night"]
    listed = texts(ok(client.get("/api/word-bank")))
    # newest first; words of one batch share created_at and follow id
    assert listed[0] == "day"
    assert sorted(listed[1:]) == ["Broken Heart", "night"]


def test_word_tags_sorted_by_lower_name(client):
    ids = [tag(client, n) for n in ("beta", "Alpha", "gamma")]
    (word,) = add(client, "x", tag_ids=ids)["created"]
    assert [t["name"] for t in word["tags"]] == ["Alpha", "beta", "gamma"]


def test_add_foreign_tag_is_not_found(client, users):
    theirs = tag(client, "love")
    users.new()
    assert_error(client.post("/api/word-bank", json={"words": ["a"], "tag_ids": [theirs]}), 404, "tag_not_found")
    assert ok(client.get("/api/word-bank")) == []


def test_banks_are_per_user(client, users):
    add(client, "night")
    users.new()
    assert ok(client.get("/api/word-bank")) == []
    assert texts(add(client, "night")["created"]) == ["night"]


# ---- rename ----


def test_rename(client):
    a, b = add(client, "night", "day")["created"]
    assert ok(client.put(f"/api/word-bank/{a['id']}", json={"text": "Night"}))["text"] == "Night"
    assert ok(client.put(f"/api/word-bank/{a['id']}", json={"text": "  mid   night "}))["text"] == "mid night"
    assert_error(client.put(f"/api/word-bank/{b['id']}", json={"text": "MID NIGHT"}), 409, "word_exists")
    assert_error(client.put(f"/api/word-bank/{b['id']}", json={"text": "d4y"}), 422, "invalid_word")
    assert_error(client.put(f"/api/word-bank/{uuid.uuid4()}", json={"text": "x"}), 404, "word_not_found")


def test_rename_foreign_word(client, users):
    (word,) = add(client, "night")["created"]
    users.new()
    assert_error(client.put(f"/api/word-bank/{word['id']}", json={"text": "day"}), 404, "word_not_found")


# ---- delete ----


def test_delete_ignores_foreign_ids(client, users):
    (mine,) = add(client, "mine")["created"]
    users.new()
    theirs_user = users.current
    (theirs,) = add(client, "theirs")["created"]
    (keep,) = add(client, "keep")["created"]
    assert ok(client.post("/api/word-bank/delete", json={"ids": [mine["id"], theirs["id"], "junk"]}), 204) is None
    assert texts(ok(client.get("/api/word-bank"))) == ["keep"]
    assert theirs_user == users.current


# ---- tags ----


def test_link_unlink(client):
    love = tag(client, "love")
    a, b = add(client, "a", "b")["created"]
    body = {"word_ids": [b["id"], a["id"]], "tag_id": love, "op": "link"}
    words = ok(client.post("/api/word-bank/tags", json=body))["words"]
    assert texts(words) == ["b", "a"]
    assert all(w["tags"] == [{"id": love, "name": "love", "color": "#6366f1"}] for w in words)
    ok(client.post("/api/word-bank/tags", json=body))  # idempotent
    tags = {t["name"]: t for t in ok(client.get("/api/tags"))}
    assert (tags["love"]["word_count"], tags["love"]["snippet_count"]) == (2, 0)
    words = ok(client.post("/api/word-bank/tags", json={**body, "op": "unlink"}))["words"]
    assert all(w["tags"] == [] for w in words)
    assert ok(client.get("/api/tags"))[0]["word_count"] == 0


def test_link_with_foreign_word_changes_nothing(client, users):
    love = tag(client, "love")
    (mine,) = add(client, "mine")["created"]
    owner = users.current
    users.new()
    (theirs,) = add(client, "theirs")["created"]
    users.current = owner
    body = {"word_ids": [mine["id"], theirs["id"]], "tag_id": love, "op": "link"}
    assert_error(client.post("/api/word-bank/tags", json=body), 404, "word_not_found")
    assert by_text(client)["mine"]["tags"] == []
    body = {"word_ids": [mine["id"], str(uuid.uuid4())], "tag_id": love, "op": "link"}
    assert_error(client.post("/api/word-bank/tags", json=body), 404, "word_not_found")


def test_link_unknown_tag(client):
    (a,) = add(client, "a")["created"]
    body = {"word_ids": [a["id"]], "tag_id": str(uuid.uuid4()), "op": "link"}
    assert_error(client.post("/api/word-bank/tags", json=body), 404, "tag_not_found")


def test_tag_delete_removes_word_links(client):
    love = tag(client, "love")
    add(client, "night", tag_ids=[love])
    ok(client.delete(f"/api/tags/{love}"), 204)
    assert by_text(client)["night"]["tags"] == []


def test_tag_response_has_word_count(client):
    love = tag(client, "love")
    add(client, "a", "b", tag_ids=[love])
    (t,) = ok(client.get("/api/tags"))
    assert list(t) == ["id", "name", "color", "snippet_count", "word_count", "created_at"]
    assert t["word_count"] == 2
    renamed = ok(client.put(f"/api/tags/{love}", json={"name": "Love"}))
    assert renamed["word_count"] == 2


# ---- rhymes ----


def test_rhymes_by_word_id(client):
    words = add(client, "night", "light", "Fight", "money", "delight")["created"]
    night = words[0]
    res = ok(client.post("/api/word-bank/rhymes", json={"word_id": night["id"]}))
    assert res["anchor"] == {"text": "night", "rhyme_key": ["AY", "T"], "syllable_count": 1}
    matched = [(m["word"]["text"], m["score"], m["match_depth"]) for m in res["matches"]]
    assert matched[:3] == [("delight", 1.0, 1), ("Fight", 1.0, 1), ("light", 1.0, 1)]
    assert "night" not in [m[0] for m in matched]
    assert "money" not in [m[0] for m in matched]
    assert set(res["matches"][0]) == {"word", "score", "match_depth"}


def test_rhymes_by_text_excludes_equal_text(client):
    add(client, "night", "light", "bite")
    res = ok(client.post("/api/word-bank/rhymes", json={"text": "LIGHT", "limit": 1}))
    assert res["anchor"]["text"] == "LIGHT"
    assert texts([m["word"] for m in res["matches"]]) == ["bite"]


def test_rhymes_min_score_and_filter(client):
    dark = tag(client, "dark")
    add(client, "night", tag_ids=[dark])
    add(client, "light", "money")
    res = ok(client.post("/api/word-bank/rhymes", json={"text": "fight", "tag_filter": "dark"}))
    assert texts([m["word"] for m in res["matches"]]) == ["night"]
    res = ok(client.post("/api/word-bank/rhymes", json={"text": "fight", "min_score": 0.0}))
    assert "money" in texts([m["word"] for m in res["matches"]])
    res = ok(client.post("/api/word-bank/rhymes", json={"text": "fight", "tag_filter": "!type:verse"}))
    assert len(res["matches"]) == 2


def test_rhymes_foreign_word_id(client, users):
    (word,) = add(client, "night")["created"]
    users.new()
    assert_error(client.post("/api/word-bank/rhymes", json={"word_id": word["id"]}), 404, "word_not_found")


def test_rhymes_unpronounceable_anchor(client):
    (word,) = add(client, "夜")["created"]
    add(client, "night")
    res = ok(client.post("/api/word-bank/rhymes", json={"word_id": word["id"]}))
    assert res == {"anchor": {"text": "夜", "rhyme_key": [], "syllable_count": 0}, "matches": []}


# ---- families ----


def test_families(client):
    dark = tag(client, "dark")
    add(client, "night", "light", "fight", "money", "honey", "table", tag_ids=[dark])
    add(client, "bite")
    res = ok(client.post("/api/word-bank/families", json={"tag_filter": "dark"}))
    fams = res["families"]
    assert [sorted(texts(f["words"])) for f in fams] == [["fight", "light", "night"], ["honey", "money"]]
    assert fams[0]["label"] == fams[0]["words"][0]["text"]
    assert fams[0]["rhyme_key"] == ["AY", "T"]
    assert fams[0]["cohesion"] == 1.0
    assert set(fams[0]) == {"label", "rhyme_key", "words", "cohesion"}
    assert texts(res["unmatched"]) == ["table"]
    everything = ok(client.post("/api/word-bank/families", json={}))
    assert sorted(texts(everything["families"][0]["words"])) == ["bite", "fight", "light", "night"]


def test_families_empty_bank(client):
    assert ok(client.post("/api/word-bank/families", json={})) == {"families": [], "unmatched": []}


def test_families_too_many_words(client):
    letters = "abcdefghijklmnopqrstuvwxyz"
    words = [f"{a}{b}{c}" for a in letters for b in letters for c in "abc"][:1001]
    for start in range(0, len(words), 200):
        add(client, *words[start : start + 200])
    assert_error(client.post("/api/word-bank/families", json={}), 422, "too_many_words")
    # a filter that narrows the bank works
    assert ok(client.post("/api/word-bank/families", json={"tag_filter": "nothing"})) == {"families": [], "unmatched": []}


# ---- related ----


def test_related_marks_words_already_in_the_bank(client, related):
    (moon,) = add(client, "Moon")["created"]
    related.hits = [RelatedHit("dark", 0.8), RelatedHit("moon", 0.7)]
    res = ok(client.get("/api/word-bank/related", params={"q": "night"}))
    assert res == {
        "query": "night",
        "results": [
            {"text": "dark", "similarity": 0.8, "bank_word_id": None},
            {"text": "moon", "similarity": 0.7, "bank_word_id": moon["id"]},
        ],
    }
