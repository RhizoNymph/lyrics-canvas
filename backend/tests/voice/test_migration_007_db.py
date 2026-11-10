"""Migration 007: the six nullable dataset columns on snippet_recordings, the verified pair check,
and a downgrade that drops them all. See tests/tags/db.py for pointing this at a throwaway database."""

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

USER = uuid.UUID("aaaaaaaa-0000-0000-0000-000000000007")
CANVAS = uuid.UUID("cccccccc-0000-0000-0000-000000000007")
SNIPPET = uuid.UUID("dddddddd-0000-0000-0000-000000000007")
OLD = uuid.UUID("eeeeeeee-0000-0000-0000-000000000007")
NEW_COLUMNS = {"words", "line_spans", "language", "initial_prompt", "verified_text", "verified_at"}


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


def columns() -> dict[str, tuple]:
    rows = one(
        "SELECT column_name, data_type, is_nullable, character_maximum_length FROM information_schema.columns "
        "WHERE table_name = 'snippet_recordings'"
    )
    return {r[0]: (r[1], r[2], r[3]) for r in rows}


def insert_recording(recording_id: uuid.UUID, **extra) -> None:
    names = ", ".join(["id", "snippet_id", "mime_type", "duration_ms", "byte_size", "audio", "transcript", "split_mode", *extra])
    values = ", ".join([":id", ":s", "'audio/ogg'", "10", "1", "'\\x00'::bytea", "'a'", "'taps'", *(f":{k}" for k in extra)])
    one(f"INSERT INTO snippet_recordings ({names}) VALUES ({values})", id=recording_id, s=SNIPPET, **extra)


@pytest.fixture(scope="module", autouse=True)
def migrated():
    alembic("downgrade", "base")
    alembic("upgrade", "006")
    asyncio.run(_run(
        ("INSERT INTO users (id, email, display_name) VALUES (:u, 'm7@x.test', 'm')", {"u": USER}),
        ("INSERT INTO canvases (id, owner_id, title) VALUES (:c, :u, 'c')", {"c": CANVAS, "u": USER}),
        ("INSERT INTO snippets (id, canvas_id, raw_text) VALUES (:s, :c, 'a')", {"s": SNIPPET, "c": CANVAS}),
    ))
    insert_recording(OLD)  # a row from before 007
    alembic("upgrade", "head")
    yield
    alembic("downgrade", "base")


def test_columns_are_added_nullable_with_their_types():
    cols = columns()
    assert cols["words"][:2] == ("jsonb", "YES")
    assert cols["line_spans"][:2] == ("jsonb", "YES")
    assert cols["language"] == ("character varying", "YES", 8)
    assert cols["initial_prompt"][:2] == ("text", "YES")
    assert cols["verified_text"][:2] == ("text", "YES")
    assert cols["verified_at"][:2] == ("timestamp with time zone", "YES")


def test_existing_rows_keep_nulls():
    (row,) = one(
        "SELECT words, line_spans, language, initial_prompt, verified_text, verified_at FROM snippet_recordings WHERE id = :id",
        id=OLD,
    )
    assert tuple(row) == (None,) * 6


def test_verified_text_and_time_are_both_set_or_both_null():
    with pytest.raises(IntegrityError):
        insert_recording(uuid.uuid4(), verified_text="x")
    with pytest.raises(IntegrityError):
        one("UPDATE snippet_recordings SET verified_at = now() WHERE id = :id", id=OLD)
    one("UPDATE snippet_recordings SET verified_text = 'x', verified_at = now() WHERE id = :id", id=OLD)
    one("UPDATE snippet_recordings SET verified_text = NULL, verified_at = NULL WHERE id = :id", id=OLD)


def test_jsonb_columns_hold_words_and_spans():
    rid = uuid.uuid4()
    insert_recording(rid)
    one(
        "UPDATE snippet_recordings SET words = CAST(:w AS jsonb), line_spans = CAST(:l AS jsonb) WHERE id = :id",
        id=rid, w='[{"text": "a", "start_ms": 0, "end_ms": 5, "probability": null}]', l="[[0, 1]]",
    )
    (row,) = one("SELECT words, line_spans FROM snippet_recordings WHERE id = :id", id=rid)
    assert row[0][0]["text"] == "a" and row[1] == [[0, 1]]


def test_downgrade_drops_all_six_columns_and_upgrade_restores_them():
    alembic("downgrade", "006")
    assert not (NEW_COLUMNS & set(columns()))
    assert one("SELECT count(*) FROM snippet_recordings WHERE id = :id", id=OLD)[0][0] == 1
    alembic("upgrade", "head")
    assert NEW_COLUMNS <= set(columns())
    assert one("SELECT version_num FROM alembic_version")[0][0] == "007"
