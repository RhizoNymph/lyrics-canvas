"""Migration 006: bank_words / bank_word_tags, their indexes and cascades; the downgrade drops both.

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

USER_A = uuid.UUID("aaaaaaaa-0000-0000-0000-000000000006")
USER_B = uuid.UUID("bbbbbbbb-0000-0000-0000-000000000006")
TAG_A = uuid.UUID("10000000-0000-0000-0000-000000000006")
WORD_A = uuid.UUID("20000000-0000-0000-0000-000000000006")


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


def run(*statements: tuple[str, dict]) -> list:
    return asyncio.run(_run(*statements))


def one(sql: str, **params) -> list:
    return run((sql, params))[0]


def tables() -> set[str]:
    rows = one("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")
    return {r[0] for r in rows}


@pytest.fixture(scope="module", autouse=True)
def at_head():
    alembic("downgrade", "base")
    alembic("upgrade", "head")
    yield
    alembic("downgrade", "base")


def seed() -> None:
    run(
        ("DELETE FROM users", {}),
        ("INSERT INTO users (id, email, display_name) VALUES (:a, 'a@x.test', 'a'), (:b, 'b@x.test', 'b')",
         {"a": USER_A, "b": USER_B}),
        ("INSERT INTO tags (id, user_id, name, color) VALUES (:t, :a, 'love', '#6366f1')", {"t": TAG_A, "a": USER_A}),
        ("INSERT INTO bank_words (id, user_id, text) VALUES (:w, :a, 'Night')", {"w": WORD_A, "a": USER_A}),
        ("INSERT INTO bank_word_tags (bank_word_id, tag_id) VALUES (:w, :t)", {"w": WORD_A, "t": TAG_A}),
    )


def test_head_has_both_tables_and_indexes():
    assert {"bank_words", "bank_word_tags"} <= tables()
    indexes = {r[0]: r[1] for r in one("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'bank_words'")}
    assert "ix_bank_words_user_id" in indexes
    assert "UNIQUE" in indexes["uq_bank_words_user_lower_text"]
    assert "lower" in indexes["uq_bank_words_user_lower_text"]
    pk = one(
        "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
        "WHERE i.indrelid = 'bank_word_tags'::regclass AND i.indisprimary"
    )
    assert {r[0] for r in pk} == {"bank_word_id", "tag_id"}
    cols = {r[0]: (r[1], r[2], r[3]) for r in one(
        "SELECT column_name, data_type, is_nullable, character_maximum_length FROM information_schema.columns "
        "WHERE table_name = 'bank_words'"
    )}
    assert cols["text"] == ("character varying", "NO", 64)
    assert cols["created_at"][:2] == ("timestamp with time zone", "NO")
    assert cols["user_id"][:2] == ("uuid", "NO")


def test_text_unique_per_user_ignoring_case():
    seed()
    with pytest.raises(IntegrityError):
        one("INSERT INTO bank_words (id, user_id, text) VALUES (gen_random_uuid(), :a, 'NIGHT')", a=USER_A)
    # another user may hold the same word
    one("INSERT INTO bank_words (id, user_id, text) VALUES (gen_random_uuid(), :b, 'night')", b=USER_B)
    created = one("SELECT created_at FROM bank_words WHERE id = :w", w=WORD_A)
    assert created[0][0] is not None


def test_tag_delete_cascades_to_links():
    seed()
    one("DELETE FROM tags WHERE id = :t", t=TAG_A)
    assert one("SELECT count(*) FROM bank_word_tags")[0][0] == 0
    assert one("SELECT count(*) FROM bank_words WHERE id = :w", w=WORD_A)[0][0] == 1


def test_word_delete_cascades_to_links():
    seed()
    one("DELETE FROM bank_words WHERE id = :w", w=WORD_A)
    assert one("SELECT count(*) FROM bank_word_tags")[0][0] == 0
    assert one("SELECT count(*) FROM tags WHERE id = :t", t=TAG_A)[0][0] == 1


def test_user_delete_cascades_to_words():
    seed()
    one("DELETE FROM users WHERE id = :a", a=USER_A)
    assert one("SELECT count(*) FROM bank_words WHERE user_id = :a", a=USER_A)[0][0] == 0
    assert one("SELECT count(*) FROM bank_word_tags")[0][0] == 0


def test_downgrade_drops_both_tables_and_upgrade_restores_them():
    seed()
    alembic("downgrade", "005")
    assert not ({"bank_words", "bank_word_tags"} & tables())
    assert "tags" in tables()
    alembic("upgrade", "head")
    assert {"bank_words", "bank_word_tags"} <= tables()
    assert one("SELECT version_num FROM alembic_version")[0][0] == "006"
