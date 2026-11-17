"""Migration 011 on Postgres, both directions: ``api_tokens`` with its constraints, cascade from
users, and a clean downgrade back to 010. See tests/tags/db.py for pointing this at a throwaway database."""

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

USER = uuid.UUID("00000000-0000-0000-0011-000000000001")


async def _run(sql: str, params: dict) -> list | None:
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    try:
        async with engine.begin() as conn:
            result = await conn.execute(text(sql), params)
            return result.fetchall() if result.returns_rows else None
    finally:
        await engine.dispose()


def one(sql: str, **params) -> list | None:
    return asyncio.run(_run(sql, params))


def has_table() -> bool:
    return bool(one("SELECT 1 FROM information_schema.tables WHERE table_name = 'api_tokens'"))


def insert_token(token_hash: str) -> None:
    one(
        "INSERT INTO api_tokens (id, user_id, name, token_hash, prefix) VALUES (gen_random_uuid(), :u, 'n', :h, 'lct_abcdefgh')",
        u=USER, h=token_hash,
    )


@pytest.fixture
def at_010():
    alembic("downgrade", "base")
    alembic("upgrade", "010")
    one("INSERT INTO users (id, email, display_name) VALUES (:u, 'm11@x.test', 'a')", u=USER)
    yield
    alembic("downgrade", "base")


def test_upgrade_creates_the_table_and_downgrade_drops_it(at_010):
    assert not has_table()
    alembic("upgrade", "011")
    assert has_table()
    assert one("SELECT 1 FROM pg_indexes WHERE indexname = 'ix_api_tokens_user_id'")
    insert_token("a" * 64)
    (row,) = one("SELECT created_at IS NOT NULL, last_used_at, expires_at, revoked_at FROM api_tokens")
    assert row == (True, None, None, None)
    alembic("downgrade", "010")
    assert not has_table()
    assert one("SELECT count(*) FROM users WHERE id = :u", u=USER) == [(1,)]


def test_token_hash_is_unique_and_tokens_cascade_with_the_user(at_010):
    alembic("upgrade", "011")
    insert_token("b" * 64)
    with pytest.raises(IntegrityError):
        insert_token("b" * 64)
    one("DELETE FROM users WHERE id = :u", u=USER)
    assert one("SELECT count(*) FROM api_tokens") == [(0,)]
