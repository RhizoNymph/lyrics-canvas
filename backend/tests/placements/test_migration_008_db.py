"""Migration 008 on Postgres, both directions, with seeded data: snippets with layout, groups (one
pointing at another canvas's group), tags and recordings; then multi-placement snippets for the
downgrade. See tests/tags/db.py for pointing this at a throwaway database."""

from __future__ import annotations

import asyncio
import uuid
from datetime import datetime, timezone

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from tests.tags.db import DB_URL, alembic, requires_db

pytestmark = requires_db


def u(n: int) -> uuid.UUID:
    return uuid.UUID(f"00000000-0000-0000-0008-{n:012d}")


ALICE, BOB = u(1), u(2)
SONG, DRAFTS, BOBS = u(10), u(11), u(12)  # canvases: Alice's two, Bob's one
G_SONG, G_DRAFTS = u(20), u(21)
S1, S2, S3, S4 = u(30), u(31), u(32), u(33)  # S1, S2 on SONG; S3 on DRAFTS; S4 on BOBS
TAG = u(40)
REC1, REC3 = u(50), u(51)
LAYOUT_COLUMNS = {"canvas_id", "position_x", "position_y", "width", "height", "z_index", "group_id"}


async def _run(*statements: tuple[str, dict]) -> list:
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    out = []
    try:
        async with engine.begin() as conn:
            for sql, params in statements:
                result = await conn.execute(text(sql), params)
                out.append(result.fetchall() if result.returns_rows else None)
    finally:
        await engine.dispose()
    return out


def one(sql: str, **params) -> list:
    return asyncio.run(_run((sql, params)))[0]


def columns(table: str) -> set[str]:
    return {r[0] for r in one("SELECT column_name FROM information_schema.columns WHERE table_name = :t", t=table)}


def table_exists(table: str) -> bool:
    return one("SELECT to_regclass(:t) IS NOT NULL", t=f"public.{table}")[0][0]


def seed_007() -> None:
    snippet = (
        "INSERT INTO snippets (id, canvas_id, raw_text, snippet_type, grid_mode, grid_data, position_x, position_y, "
        "width, height, z_index, group_id, created_at) VALUES "
        "(:id, :c, :t, 'verse', 'auto', CAST('{\"lines\": []}' AS jsonb), :x, :y, :w, :h, :z, :g, :at)"
    )
    recording = (
        "INSERT INTO snippet_recordings (id, snippet_id, mime_type, duration_ms, byte_size, audio, transcript, split_mode) "
        "VALUES (:id, :s, 'audio/ogg', 10, 1, '\\x00'::bytea, 'a', 'taps')"
    )
    asyncio.run(_run(
        ("INSERT INTO users (id, email, display_name) VALUES (:a, 'm8a@x.test', 'a'), (:b, 'm8b@x.test', 'b')", {"a": ALICE, "b": BOB}),
        (
            "INSERT INTO canvases (id, owner_id, title) VALUES (:s, :a, 'Song'), (:d, :a, 'drafts'), (:o, :b, 'Bob')",
            {"s": SONG, "d": DRAFTS, "o": BOBS, "a": ALICE, "b": BOB},
        ),
        ("INSERT INTO groups (id, canvas_id, name) VALUES (:g1, :s, 'Verse'), (:g2, :d, 'Other')", {"g1": G_SONG, "g2": G_DRAFTS, "s": SONG, "d": DRAFTS}),
        (snippet, dict(id=S1, c=SONG, t="one", x=10.5, y=20.0, w=310.0, h=90.0, z=3, g=G_SONG, at=datetime(2026, 1, 1, tzinfo=timezone.utc))),
        # a stray group of another canvas: not representable on a placement
        (snippet, dict(id=S2, c=SONG, t="two", x=-5.0, y=7.0, w=200.0, h=80.0, z=1, g=G_DRAFTS, at=datetime(2026, 1, 2, tzinfo=timezone.utc))),
        (snippet, dict(id=S3, c=DRAFTS, t="three", x=0.0, y=0.0, w=300.0, h=200.0, z=0, g=None, at=datetime(2026, 1, 3, tzinfo=timezone.utc))),
        (snippet, dict(id=S4, c=BOBS, t="four", x=1.0, y=2.0, w=300.0, h=200.0, z=0, g=None, at=datetime(2026, 1, 4, tzinfo=timezone.utc))),
        ("INSERT INTO tags (id, user_id, name) VALUES (:t, :a, 'love')", {"t": TAG, "a": ALICE}),
        ("INSERT INTO snippet_tags (snippet_id, tag_id) VALUES (:s, :t)", {"s": S1, "t": TAG}),
        (recording, {"id": REC1, "s": S1}),
        (recording, {"id": REC3, "s": S3}),
    ))


@pytest.fixture(scope="module", autouse=True)
def migrated():
    alembic("downgrade", "base")
    alembic("upgrade", "007")
    seed_007()
    alembic("upgrade", "008")
    yield
    alembic("downgrade", "base")


def placements() -> dict[uuid.UUID, tuple]:
    rows = one(
        "SELECT snippet_id, canvas_id, position_x, position_y, width, height, z_index, group_id FROM snippet_placements"
    )
    return {r[0]: tuple(r[1:]) for r in rows}


# ---- upgrade ----


def test_upgrade_creates_one_placement_per_snippet_from_its_layout():
    assert placements() == {
        S1: (SONG, 10.5, 20.0, 310.0, 90.0, 3, G_SONG),
        S2: (SONG, -5.0, 7.0, 200.0, 80.0, 1, None),  # the other canvas's group is dropped
        S3: (DRAFTS, 0.0, 0.0, 300.0, 200.0, 0, None),
        S4: (BOBS, 1.0, 2.0, 300.0, 200.0, 0, None),
    }


def test_upgrade_sets_owner_from_the_canvas_and_drops_the_layout_columns():
    owners = dict(one("SELECT id, owner_id FROM snippets"))
    assert owners == {S1: ALICE, S2: ALICE, S3: ALICE, S4: BOB}
    cols = columns("snippets")
    assert "owner_id" in cols
    assert not (LAYOUT_COLUMNS & cols)
    (nullable,) = one("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'snippets' AND column_name = 'owner_id'")
    assert nullable[0] == "NO"


def test_upgrade_keeps_content_tags_and_recordings():
    assert dict(one("SELECT id, raw_text FROM snippets")) == {S1: "one", S2: "two", S3: "three", S4: "four"}
    assert one("SELECT snippet_id, tag_id FROM snippet_tags") == [(S1, TAG)]
    assert dict(one("SELECT id, snippet_id FROM snippet_recordings")) == {REC1: S1, REC3: S3}


def test_placement_table_constraints_and_indexes():
    indexes = {r[0] for r in one("SELECT indexname FROM pg_indexes WHERE tablename = 'snippet_placements'")}
    assert {"ix_snippet_placements_canvas_id", "ix_snippet_placements_snippet_id", "uq_snippet_placements_snippet_canvas"} <= indexes
    assert "ix_snippets_owner_id" in {r[0] for r in one("SELECT indexname FROM pg_indexes WHERE tablename = 'snippets'")}
    with pytest.raises(IntegrityError):  # one placement per (snippet, canvas)
        one("INSERT INTO snippet_placements (id, snippet_id, canvas_id) VALUES (gen_random_uuid(), :s, :c)", s=S1, c=SONG)
    rules = dict(one(
        "SELECT kcu.column_name, rc.delete_rule FROM information_schema.referential_constraints rc "
        "JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = rc.constraint_name "
        "WHERE kcu.table_name = 'snippet_placements'"
    ))
    assert rules == {"snippet_id": "CASCADE", "canvas_id": "CASCADE", "group_id": "SET NULL"}
    (defaults,) = one(
        "INSERT INTO snippet_placements (id, snippet_id, canvas_id) VALUES (gen_random_uuid(), :s, :c) "
        "RETURNING position_x, position_y, width, height, z_index, group_id, created_at IS NOT NULL",
        s=S3, c=SONG,
    )
    assert tuple(defaults) == (0.0, 0.0, 300.0, 200.0, 0, None, True)
    one("DELETE FROM snippet_placements WHERE snippet_id = :s AND canvas_id = :c", s=S3, c=SONG)


# ---- downgrade (runs last: it rewrites the data) ----


def test_zz_downgrade_splits_extra_placements_into_duplicated_snippets():
    # S1 is also on DRAFTS (newer placement) and grouped there; S3 also on SONG
    asyncio.run(_run(
        (
            "INSERT INTO snippet_placements (id, snippet_id, canvas_id, position_x, position_y, width, height, z_index, group_id, created_at) "
            "VALUES (:id, :s, :c, 99.0, 98.0, 400.0, 120.0, 7, :g, TIMESTAMPTZ '2030-01-01T00:00:00Z')",
            {"id": u(60), "s": S1, "c": DRAFTS, "g": G_DRAFTS},
        ),
        (
            "INSERT INTO snippet_placements (id, snippet_id, canvas_id, position_x, position_y, z_index, created_at) "
            "VALUES (:id, :s, :c, 5.0, 6.0, 2, TIMESTAMPTZ '2030-01-02T00:00:00Z')",
            {"id": u(61), "s": S3, "c": SONG},
        ),
    ))
    alembic("downgrade", "007")

    assert not table_exists("snippet_placements")
    cols = columns("snippets")
    assert LAYOUT_COLUMNS <= cols and "owner_id" not in cols
    rows = one(
        "SELECT id, canvas_id, raw_text, position_x, position_y, width, height, z_index, group_id, snippet_type::text FROM snippets"
    )
    by_id = {r[0]: tuple(r[1:]) for r in rows}
    # the oldest placement keeps the snippet's id
    assert by_id[S1] == (SONG, "one", 10.5, 20.0, 310.0, 90.0, 3, G_SONG, "verse")
    assert by_id[S2] == (SONG, "two", -5.0, 7.0, 200.0, 80.0, 1, None, "verse")
    assert by_id[S3] == (DRAFTS, "three", 0.0, 0.0, 300.0, 200.0, 0, None, "verse")
    assert by_id[S4] == (BOBS, "four", 1.0, 2.0, 300.0, 200.0, 0, None, "verse")
    copies = {k: v for k, v in by_id.items() if k not in {S1, S2, S3, S4}}
    assert sorted(copies.values(), key=lambda v: v[1]) == [
        (DRAFTS, "one", 99.0, 98.0, 400.0, 120.0, 7, G_DRAFTS, "verse"),
        (SONG, "three", 5.0, 6.0, 300.0, 200.0, 2, None, "verse"),
    ]
    copy_of_s1 = next(k for k, v in copies.items() if v[1] == "one")
    # tag links are copied, recordings are not
    assert sorted(one("SELECT snippet_id, tag_id FROM snippet_tags"), key=str) == sorted([(S1, TAG), (copy_of_s1, TAG)], key=str)
    assert dict(one("SELECT id, snippet_id FROM snippet_recordings")) == {REC1: S1, REC3: S3}
    # content timestamps travel with the copy
    (same,) = one(
        "SELECT a.created_at = b.created_at AND a.content_updated_at = b.content_updated_at FROM snippets a, snippets b "
        "WHERE a.id = :a AND b.id = :b",
        a=S1, b=copy_of_s1,
    )
    assert same[0] is True
    # 007's schema is back: canvas_id is required again
    with pytest.raises(IntegrityError):
        one("INSERT INTO snippets (id, raw_text) VALUES (gen_random_uuid(), 'x')")


def test_zz_upgrade_after_downgrade_round_trips():
    alembic("upgrade", "008")
    assert one("SELECT version_num FROM alembic_version")[0][0] == "008"
    assert one("SELECT count(*) FROM snippet_placements")[0][0] == one("SELECT count(*) FROM snippets")[0][0] == 6
