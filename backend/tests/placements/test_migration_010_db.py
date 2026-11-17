"""Migration 010 on Postgres, both directions: the ``list_rank`` backfill gives each canvas
``a0, a1, ...`` in the old list order (snippet content_updated_at desc, created_at desc, id), per
canvas for snippets on several canvases; the column is NOT NULL, byte-ordered and indexed; the
downgrade drops it and keeps the rows. See tests/tags/db.py for pointing this at a throwaway database."""

from __future__ import annotations

import asyncio
import importlib.util
import uuid
from datetime import datetime, timedelta, timezone
from itertools import islice
from pathlib import Path

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from app.services.placements.list_rank import n_keys_between
from tests.tags.db import DB_URL, alembic, requires_db

MIGRATION = Path(__file__).resolve().parents[2] / "alembic" / "versions" / "010_placement_list_rank.py"
T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


def u(n: int) -> uuid.UUID:
    return uuid.UUID(f"00000000-0000-0000-0010-{n:012d}")


USER = u(1)
SONG, DRAFTS, BIG, EMPTY = u(10), u(11), u(12), u(13)
# On SONG: content_updated_at decides; A and B tie on it, so created_at decides; C and D tie on both,
# so the id decides (C < D).
A, B, C, D, NEWEST = u(20), u(21), u(22), u(23), u(24)
SHARED = u(25)  # on SONG and DRAFTS
LONE = u(26)  # on DRAFTS only


def _migration_module():
    spec = importlib.util.spec_from_file_location("migration_010", MIGRATION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_frozen_sequential_keys_match_the_port():
    module = _migration_module()
    for n in (1, 2, 61, 62, 63, 3844, 3845, 4000):
        assert list(islice(module.sequential_keys(), n)) == n_keys_between(None, None, n)


async def _run(*statements: tuple[str, object]) -> list:
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


def _snippet(sid: uuid.UUID, content_at: datetime, created_at: datetime) -> tuple[str, dict]:
    return (
        "INSERT INTO snippets (id, owner_id, raw_text, snippet_type, grid_mode, content_updated_at, created_at) "
        "VALUES (:s, :u, 'x', 'verse', 'auto', :c, :k)",
        {"s": sid, "u": USER, "c": content_at, "k": created_at},
    )


def _place(sid: uuid.UUID, canvas: uuid.UUID) -> tuple[str, dict]:
    return ("INSERT INTO snippet_placements (id, snippet_id, canvas_id) VALUES (gen_random_uuid(), :s, :c)", {"s": sid, "c": canvas})


BIG_IDS = [uuid.UUID(f"00000000-0000-0000-0010-1{n:011d}") for n in range(70)]


def seed_009() -> None:
    hour = timedelta(hours=1)
    statements: list[tuple[str, object]] = [
        ("INSERT INTO users (id, email, display_name) VALUES (:u, 'm10@x.test', 'a')", {"u": USER}),
        *[
            ("INSERT INTO canvases (id, owner_id, title) VALUES (:c, :u, :t)", {"c": c, "u": USER, "t": t})
            for c, t in ((SONG, "Song"), (DRAFTS, "Drafts"), (BIG, "Big"), (EMPTY, "Empty"))
        ],
        _snippet(A, T0 + 2 * hour, T0),
        _snippet(B, T0 + 2 * hour, T0 + hour),
        _snippet(C, T0 + hour, T0),
        _snippet(D, T0 + hour, T0),
        _snippet(NEWEST, T0 + 5 * hour, T0),
        _snippet(SHARED, T0 + 3 * hour, T0),
        _snippet(LONE, T0 + 4 * hour, T0),
    ]
    for sid in (D, C, B, A, NEWEST, SHARED):
        statements.append(_place(sid, SONG))
    for sid in (SHARED, LONE):
        statements.append(_place(sid, DRAFTS))
    for i, sid in enumerate(BIG_IDS):
        # BIG_IDS[0] edited last: first in the list
        statements.append(_snippet(sid, T0 + timedelta(minutes=100 - i), T0))
        statements.append(_place(sid, BIG))
    asyncio.run(_run(*statements))


def ranks_on(canvas: uuid.UUID) -> dict[uuid.UUID, str]:
    return dict(one("SELECT snippet_id, list_rank FROM snippet_placements WHERE canvas_id = :c", c=canvas))


def has_column() -> bool:
    return bool(one(
        "SELECT 1 FROM information_schema.columns WHERE table_name = 'snippet_placements' AND column_name = 'list_rank'"
    ))


def has_index() -> bool:
    return bool(one("SELECT 1 FROM pg_indexes WHERE indexname = 'ix_snippet_placements_canvas_list_rank'"))


@pytest.fixture
def at_009():
    alembic("downgrade", "base")
    alembic("upgrade", "009")
    seed_009()
    yield
    alembic("downgrade", "base")


@requires_db
def test_backfill_ranks_each_canvas_in_the_old_list_order(at_009):
    assert not has_column()
    alembic("upgrade", "010")
    assert ranks_on(SONG) == {NEWEST: "a0", SHARED: "a1", B: "a2", A: "a3", C: "a4", D: "a5"}
    assert ranks_on(DRAFTS) == {LONE: "a0", SHARED: "a1"}
    big = ranks_on(BIG)
    assert [big[sid] for sid in BIG_IDS] == n_keys_between(None, None, 70)
    assert big[BIG_IDS[62]] == "b00"
    assert ranks_on(EMPTY) == {}


@requires_db
def test_column_is_not_null_byte_ordered_and_indexed(at_009):
    alembic("upgrade", "010")
    assert has_index()
    (row,) = one(
        "SELECT is_nullable, character_maximum_length, collation_name FROM information_schema.columns "
        "WHERE table_name = 'snippet_placements' AND column_name = 'list_rank'"
    )
    assert row == ("NO", 64, "C")
    assert one("SELECT min(list_rank) FROM snippet_placements WHERE canvas_id = :c", c=BIG) == [("a0",)]
    one("UPDATE snippet_placements SET list_rank = 'Zz' WHERE snippet_id = :s AND canvas_id = :c", s=BIG_IDS[69], c=BIG)
    assert one("SELECT min(list_rank) FROM snippet_placements WHERE canvas_id = :c", c=BIG) == [("Zz",)]
    with pytest.raises(IntegrityError):
        one("INSERT INTO snippet_placements (id, snippet_id, canvas_id) VALUES (gen_random_uuid(), :s, :c)", s=LONE, c=SONG)


@requires_db
def test_downgrade_drops_the_column_and_keeps_the_rows_then_upgrades_again(at_009):
    alembic("upgrade", "010")
    count = one("SELECT count(*) FROM snippet_placements")
    alembic("downgrade", "009")
    assert not has_column() and not has_index()
    assert one("SELECT count(*) FROM snippet_placements") == count
    alembic("upgrade", "010")
    assert ranks_on(DRAFTS) == {LONE: "a0", SHARED: "a1"}
