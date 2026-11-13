"""Migration 009 on Postgres, both directions: existing rows of snippets, canvases, tags and
bank_words get ``version = 1``; new rows default to 1; versions below 1 are refused; the downgrade
drops the columns and keeps the rows. See tests/tags/db.py for pointing this at a throwaway database."""

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

TABLES = ("snippets", "canvases", "tags", "bank_words")


def u(n: int) -> uuid.UUID:
    return uuid.UUID(f"00000000-0000-0000-0009-{n:012d}")


USER, CANVAS, SNIPPET, TAG, WORD = u(1), u(2), u(3), u(4), u(5)


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


def has_version(table: str) -> bool:
    rows = one("SELECT 1 FROM information_schema.columns WHERE table_name = :t AND column_name = 'version'", t=table)
    return bool(rows)


def seed_008() -> None:
    asyncio.run(_run(
        ("INSERT INTO users (id, email, display_name) VALUES (:u, 'm9@x.test', 'a')", {"u": USER}),
        ("INSERT INTO canvases (id, owner_id, title) VALUES (:c, :u, 'Song')", {"c": CANVAS, "u": USER}),
        ("INSERT INTO snippets (id, owner_id, raw_text, snippet_type, grid_mode) VALUES (:s, :u, 'hi', 'verse', 'auto')", {"s": SNIPPET, "u": USER}),
        ("INSERT INTO snippet_placements (id, snippet_id, canvas_id) VALUES (:p, :s, :c)", {"p": uuid.uuid4(), "s": SNIPPET, "c": CANVAS}),
        ("INSERT INTO tags (id, user_id, name, color) VALUES (:t, :u, 'love', '#ff0000')", {"t": TAG, "u": USER}),
        ("INSERT INTO bank_words (id, user_id, text) VALUES (:w, :u, 'moon')", {"w": WORD, "u": USER}),
    ))


@pytest.fixture
def at_008():
    alembic("downgrade", "base")
    alembic("upgrade", "008")
    seed_008()
    yield
    alembic("downgrade", "base")


def test_upgrade_sets_existing_rows_to_version_1(at_008):
    assert not any(has_version(t) for t in TABLES)
    alembic("upgrade", "009")
    for table, row_id in zip(TABLES, (SNIPPET, CANVAS, TAG, WORD)):
        assert one(f"SELECT version FROM {table} WHERE id = :i", i=row_id) == [(1,)]


def test_new_rows_default_to_1_and_versions_below_1_are_refused(at_008):
    alembic("upgrade", "009")
    one("INSERT INTO tags (id, user_id, name, color) VALUES (:t, :u, 'night', '#000000')", t=u(6), u=USER)
    assert one("SELECT version FROM tags WHERE id = :t", t=u(6)) == [(1,)]
    for table, row_id in zip(TABLES, (SNIPPET, CANVAS, TAG, WORD)):
        with pytest.raises(IntegrityError):
            one(f"UPDATE {table} SET version = 0 WHERE id = :i", i=row_id)
        with pytest.raises(IntegrityError):
            one(f"UPDATE {table} SET version = NULL WHERE id = :i", i=row_id)


def test_downgrade_drops_the_columns_and_keeps_the_rows(at_008):
    alembic("upgrade", "009")
    one("UPDATE snippets SET version = 5 WHERE id = :s", s=SNIPPET)
    alembic("downgrade", "008")
    assert not any(has_version(t) for t in TABLES)
    assert one("SELECT raw_text FROM snippets WHERE id = :s", s=SNIPPET) == [("hi",)]
    assert one("SELECT count(*) FROM bank_words") == [(1,)]
    alembic("upgrade", "009")
    assert one("SELECT version FROM snippets WHERE id = :s", s=SNIPPET) == [(1,)]
