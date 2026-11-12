"""Migration 005: canvas-scoped tags become user-wide, duplicates merged; tag_presets added.

Seeds data at revision 004 with raw SQL (the ORM models describe 005), migrates and inspects.
See tests/tags/db.py for how to point this at a throwaway database.
"""

from __future__ import annotations

import asyncio
import uuid

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from tests.tags.db import DB_URL, alembic, requires_db

pytestmark = requires_db

USER_A = uuid.UUID("aaaaaaaa-0000-0000-0000-000000000001")
USER_B = uuid.UUID("bbbbbbbb-0000-0000-0000-000000000001")
CANVAS_A1 = uuid.UUID("c0000000-0000-0000-0000-0000000000a1")
CANVAS_A2 = uuid.UUID("c0000000-0000-0000-0000-0000000000a2")
CANVAS_A3 = uuid.UUID("c0000000-0000-0000-0000-0000000000a3")  # no tags, no snippets
CANVAS_B = uuid.UUID("c0000000-0000-0000-0000-0000000000b1")
# the smallest id::text of a (user, lower(name)) group survives
LOVE_A1 = uuid.UUID("20000000-0000-0000-0000-000000000000")  # "Love" on A1 (loser)
LOVE_A2 = uuid.UUID("10000000-0000-0000-0000-000000000000")  # "love" on A2 (survivor)
LOVE_A3 = uuid.UUID("30000000-0000-0000-0000-000000000000")  # "LOVE" on A3, unused (loser)
NIGHT_A2 = uuid.UUID("40000000-0000-0000-0000-000000000000")
UNUSED_A1 = uuid.UUID("50000000-0000-0000-0000-000000000000")  # "spare" on A1, no snippets
LOVE_B = uuid.UUID("05000000-0000-0000-0000-000000000000")  # another user's "love": kept apart
S1 = uuid.UUID("51000000-0000-0000-0000-000000000001")  # A1: Love
S2 = uuid.UUID("51000000-0000-0000-0000-000000000002")  # A2: love, night
S3 = uuid.UUID("51000000-0000-0000-0000-000000000003")  # A1: Love and (cross-canvas) love
S4 = uuid.UUID("51000000-0000-0000-0000-000000000004")  # B: love


async def _run(*statements: tuple[str, dict]) -> list:
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    out = []
    async with engine.begin() as conn:
        for sql, params in statements:
            result = await conn.execute(text(sql), params)
            out.append(result.fetchall() if result.returns_rows else None)
    await engine.dispose()
    return out


def run(*statements: tuple[str, dict]) -> list:
    return asyncio.run(_run(*statements))


def one(sql: str, **params) -> list:
    return run((sql, params))[0]


def seed_at_004() -> None:
    users = [(USER_A, "a"), (USER_B, "b")]
    canvases = [(CANVAS_A1, USER_A), (CANVAS_A2, USER_A), (CANVAS_A3, USER_A), (CANVAS_B, USER_B)]
    tags = [
        (LOVE_A1, CANVAS_A1, "Love", "#ff0000"),
        (LOVE_A2, CANVAS_A2, "love", "#00ff00"),
        (LOVE_A3, CANVAS_A3, "LOVE", "#0000ff"),
        (NIGHT_A2, CANVAS_A2, "night", "#6366f1"),
        (UNUSED_A1, CANVAS_A1, "spare", "#6366f1"),
        (LOVE_B, CANVAS_B, "love", "#123456"),
    ]
    snippets = [(S1, CANVAS_A1), (S2, CANVAS_A2), (S3, CANVAS_A1), (S4, CANVAS_B)]
    links = [(S1, LOVE_A1), (S2, LOVE_A2), (S2, NIGHT_A2), (S3, LOVE_A1), (S3, LOVE_A2), (S4, LOVE_B)]
    statements: list[tuple[str, dict]] = []
    statements += [("INSERT INTO users (id, email, display_name) VALUES (:id, :email, 'x')", {"id": u, "email": f"{e}@example.test"}) for u, e in users]
    statements += [("INSERT INTO canvases (id, owner_id, title) VALUES (:id, :owner, 'c')", {"id": c, "owner": o}) for c, o in canvases]
    statements += [
        ("INSERT INTO tags (id, canvas_id, name, color) VALUES (:id, :canvas, :name, :color)", {"id": t, "canvas": c, "name": n, "color": col})
        for t, c, n, col in tags
    ]
    statements += [
        ("INSERT INTO snippets (id, canvas_id, snippet_type, grid_mode, raw_text) VALUES (:id, :canvas, 'verse', 'auto', 'la')", {"id": s, "canvas": c})
        for s, c in snippets
    ]
    statements += [("INSERT INTO snippet_tags (snippet_id, tag_id) VALUES (:s, :t)", {"s": s, "t": t}) for s, t in links]
    run(*statements)


def links() -> set[tuple[uuid.UUID, uuid.UUID]]:
    return {(r[0], r[1]) for r in one("SELECT snippet_id, tag_id FROM snippet_tags")}


def columns(table: str) -> set[str]:
    return {r[0] for r in one("SELECT column_name FROM information_schema.columns WHERE table_name = :t", t=table)}


@pytest.fixture(scope="module", autouse=True)
def migrated():
    alembic("downgrade", "base")
    alembic("upgrade", "head")  # an empty database migrates cleanly
    alembic("downgrade", "004")
    seed_at_004()
    alembic("upgrade", "head")
    yield
    alembic("downgrade", "base")


def test_tags_are_user_wide_and_duplicates_merged_into_the_smallest_id():
    rows = one("SELECT id, user_id, name, color FROM tags ORDER BY id::text")
    assert [(r[0], r[1], r[2], r[3]) for r in rows] == [
        (LOVE_B, USER_B, "love", "#123456"),
        (LOVE_A2, USER_A, "love", "#00ff00"),
        (NIGHT_A2, USER_A, "night", "#6366f1"),
        (UNUSED_A1, USER_A, "spare", "#6366f1"),
    ]


def test_links_point_at_the_survivor_without_duplicates():
    assert links() == {(S1, LOVE_A2), (S2, LOVE_A2), (S2, NIGHT_A2), (S3, LOVE_A2), (S4, LOVE_B)}


def test_schema_shape():
    # version: migration 009 (docs/features/sync_versions.md)
    assert columns("tags") == {"id", "user_id", "name", "color", "created_at", "version"}
    assert columns("tag_presets") == {"id", "user_id", "name", "expression", "created_at", "updated_at"}
    created = one("SELECT bool_and(created_at IS NOT NULL) FROM tags")[0][0]
    assert created is True


def test_name_is_unique_per_user_case_insensitively():
    with pytest.raises(IntegrityError):
        one("INSERT INTO tags (id, user_id, name, color) VALUES (:id, :u, 'NIGHT', '#000000')", id=uuid.uuid4(), u=USER_A)
    # the same name for another user is fine
    fresh = uuid.uuid4()
    one("INSERT INTO tags (id, user_id, name, color) VALUES (:id, :u, 'night', '#000000')", id=fresh, u=USER_B)
    one("DELETE FROM tags WHERE id = :id", id=fresh)


def test_preset_name_is_unique_per_user_case_insensitively():
    one("INSERT INTO tag_presets (id, user_id, name, expression) VALUES (:id, :u, 'Mine', 'love')", id=uuid.uuid4(), u=USER_A)
    with pytest.raises(IntegrityError):
        one("INSERT INTO tag_presets (id, user_id, name, expression) VALUES (:id, :u, 'MINE', 'x')", id=uuid.uuid4(), u=USER_A)
    one("INSERT INTO tag_presets (id, user_id, name, expression) VALUES (:id, :u, 'mine', 'x')", id=uuid.uuid4(), u=USER_B)
    one("DELETE FROM tag_presets")


def test_deleting_a_user_cascades_to_tags_and_presets():
    user, tag = uuid.uuid4(), uuid.uuid4()
    run(
        ("INSERT INTO users (id, email, display_name) VALUES (:id, :e, 'x')", {"id": user, "e": f"{user}@example.test"}),
        ("INSERT INTO tags (id, user_id, name, color) VALUES (:id, :u, 'gone', '#000000')", {"id": tag, "u": user}),
        ("INSERT INTO tag_presets (id, user_id, name, expression) VALUES (:id, :u, 'p', 'gone')", {"id": uuid.uuid4(), "u": user}),
    )
    one("DELETE FROM users WHERE id = :id", id=user)
    assert one("SELECT count(*) FROM tags WHERE user_id = :u", u=user)[0][0] == 0
    assert one("SELECT count(*) FROM tag_presets WHERE user_id = :u", u=user)[0][0] == 0


def test_downgrade_splits_tags_per_canvas_and_upgrade_merges_again():
    alembic("downgrade", "004")
    assert "canvas_id" in columns("tags") and "user_id" not in columns("tags")
    by_snippet = {
        r[0]: (r[1], r[2], r[3])
        for r in one(
            "SELECT st.snippet_id, t.canvas_id, s.canvas_id, t.name FROM snippet_tags st "
            "JOIN tags t ON t.id = st.tag_id JOIN snippets s ON s.id = st.snippet_id"
        )
    }
    # every link points at a tag of the snippet's own canvas
    assert all(tag_canvas == snippet_canvas for tag_canvas, snippet_canvas, _ in by_snippet.values())
    names = sorted((r[0], r[1]) for r in one("SELECT canvas_id::text, name FROM tags"))
    assert names == sorted(
        [(str(CANVAS_A1), "love"), (str(CANVAS_A2), "love"), (str(CANVAS_A2), "night"), (str(CANVAS_A1), "spare"), (str(CANVAS_B), "love")]
    )
    with pytest.raises(IntegrityError):
        one("INSERT INTO tags (id, canvas_id, name, color) VALUES (:id, :c, 'night', '#000000')", id=uuid.uuid4(), c=CANVAS_A2)

    alembic("upgrade", "head")
    rows = one("SELECT user_id, name FROM tags ORDER BY user_id::text, name")
    assert [(r[0], r[1]) for r in rows] == [(USER_A, "love"), (USER_A, "night"), (USER_A, "spare"), (USER_B, "love")]
    assert len(links()) == 5
