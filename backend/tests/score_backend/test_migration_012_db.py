"""Migration 012 on Postgres, both directions: existing snippets get ``score`` NULL and
``score_view`` false; a staff view without a score is refused; the downgrade drops both columns and
keeps the rows. See tests/tags/db.py for pointing this at a throwaway database."""

from __future__ import annotations

import asyncio
import json
import uuid

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from tests.tags.db import DB_URL, alembic, requires_db

pytestmark = requires_db


def u(n: int) -> uuid.UUID:
    return uuid.UUID(f"00000000-0000-0000-0012-{n:012d}")


USER, SNIPPET = u(1), u(2)
SCORE = {"v": 1, "ppq": 48, "pickup": 0, "meters": [{"bar": 0, "num": 4, "den": 4}],
         "tempos": [{"bar": 0, "bpm": 90}], "snap": "1/16", "lines": []}


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


def columns() -> set[str]:
    rows = one("SELECT column_name FROM information_schema.columns WHERE table_name = 'snippets'")
    return {r[0] for r in rows}


@pytest.fixture
def at_011():
    alembic("downgrade", "base")
    alembic("upgrade", "011")
    asyncio.run(_run(
        ("INSERT INTO users (id, email, display_name) VALUES (:u, 'm12@x.test', 'a')", {"u": USER}),
        ("INSERT INTO snippets (id, owner_id, raw_text, snippet_type, grid_mode) VALUES (:s, :u, 'hi', 'verse', 'auto')",
         {"s": SNIPPET, "u": USER}),
    ))
    yield
    alembic("downgrade", "base")


def test_upgrade_gives_existing_rows_no_score_and_grid_view(at_011):
    assert not {"score", "score_view"} & columns()
    alembic("upgrade", "012")
    assert one("SELECT score, score_view FROM snippets WHERE id = :s", s=SNIPPET) == [(None, False)]


def test_new_rows_default_to_no_score(at_011):
    alembic("upgrade", "012")
    other = u(3)
    one("INSERT INTO snippets (id, owner_id, raw_text, snippet_type, grid_mode) VALUES (:s, :u, '', 'verse', 'auto')", s=other, u=USER)
    assert one("SELECT score, score_view FROM snippets WHERE id = :s", s=other) == [(None, False)]


def test_score_round_trips_as_jsonb(at_011):
    alembic("upgrade", "012")
    one("UPDATE snippets SET score = CAST(:j AS jsonb), score_view = true WHERE id = :s", j=json.dumps(SCORE), s=SNIPPET)
    assert one("SELECT score, score_view FROM snippets WHERE id = :s", s=SNIPPET) == [(SCORE, True)]


def test_staff_view_without_score_is_refused(at_011):
    alembic("upgrade", "012")
    with pytest.raises(IntegrityError):
        one("UPDATE snippets SET score_view = true WHERE id = :s", s=SNIPPET)


def test_downgrade_drops_columns_and_keeps_rows(at_011):
    alembic("upgrade", "012")
    one("UPDATE snippets SET score = CAST(:j AS jsonb) WHERE id = :s", j=json.dumps(SCORE), s=SNIPPET)
    alembic("downgrade", "011")
    assert not {"score", "score_view"} & columns()
    assert one("SELECT raw_text FROM snippets WHERE id = :s", s=SNIPPET) == [("hi",)]
