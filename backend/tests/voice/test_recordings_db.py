"""Recording persistence against a real, throwaway PostgreSQL database.

Skipped unless VOICE_TEST_DATABASE_URL points at a disposable database whose name contains
"test" (the schema is migrated up, down and up again, and rows are written). Never point it at a
database holding real data. Example (Compose Postgres on :5435):

    createdb -h localhost -p 5435 -U lyrics lyrics_voice_test   # pgvector must be installable
    VOICE_TEST_DATABASE_URL=postgresql+asyncpg://lyrics:lyrics_dev@localhost:5435/lyrics_voice_test \\
        uv run pytest tests/voice/test_recordings_db.py
    dropdb -h localhost -p 5435 -U lyrics lyrics_voice_test
"""

from __future__ import annotations

import os
import subprocess
import sys
import uuid
from pathlib import Path

import pytest
from sqlalchemy import func, select
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.models.canvas import Canvas
from app.models.recording import SnippetRecording, SplitMode
from app.models.snippet import Snippet, SnippetType
from app.models.user import User
from app.services import canvas_service, recording_service, snippet_service
from app.services.nlp.syllabifier import syllabify_text
from app.services.snippet_content import compute_size, derive_new_content
from app.services.voice.errors import CanvasNotFoundError, RecordingNotFoundError, SnippetNotFoundError

DB_URL = os.environ.get("VOICE_TEST_DATABASE_URL", "")
BACKEND = Path(__file__).resolve().parents[2]

pytestmark = pytest.mark.skipif(
    not DB_URL or "test" not in (make_url(DB_URL).database or ""),
    reason="set VOICE_TEST_DATABASE_URL to a throwaway database whose name contains 'test'",
)

REST = {"rest": True}
ARRANGED = {
    "lines": [
        {"cells": [{"syllable": "cat", "stress": 1, "word_index": 0}, REST, REST], "syllable_count": 1, "raw_text": "cat", "arranged": True},
        {"cells": [{"syllable": "dog", "stress": 0, "word_index": 0}], "syllable_count": 1, "raw_text": "dog"},
    ]
}


def alembic(*args: str) -> None:
    env = {**os.environ, "DATABASE_URL": DB_URL}
    subprocess.run([sys.executable, "-m", "alembic", *args], cwd=BACKEND, env=env, check=True, capture_output=True)


@pytest.fixture(scope="module", autouse=True)
def migrated():
    alembic("upgrade", "head")
    alembic("downgrade", "003")  # the downgrade must work too
    alembic("upgrade", "head")
    yield
    alembic("downgrade", "base")


@pytest.fixture
async def db():
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)()
    yield session
    await session.close()
    await engine.dispose()


async def make_owner(db: AsyncSession) -> tuple[uuid.UUID, uuid.UUID]:
    user = User(email=f"{uuid.uuid4()}@example.test", display_name="t")
    db.add(user)
    await db.flush()
    canvas = Canvas(owner_id=user.id, title="c")
    db.add(canvas)
    await db.commit()
    return user.id, canvas.id


def take(text: str = "a b\nc d", audio: bytes = b"OggS-audio", split_mode: SplitMode = SplitMode.taps) -> recording_service.NewRecording:
    return recording_service.NewRecording(mime_type="audio/ogg;codecs=opus", duration_ms=1500, audio=audio, transcript=text, split_mode=split_mode)


async def add_snippet(db: AsyncSession, canvas_id: uuid.UUID, text: str, grid: dict | None = None) -> Snippet:
    content = derive_new_content(text, grid)
    snippet = Snippet(
        canvas_id=canvas_id, raw_text=text, grid_data=content.grid_data, phonetics=content.phonetics,
        width=content.width, height=content.height,
    )
    db.add(snippet)
    await db.commit()
    return snippet


async def recording_count(db: AsyncSession, snippet_id: uuid.UUID) -> int:
    result = await db.execute(select(func.count()).select_from(SnippetRecording).where(SnippetRecording.snippet_id == snippet_id))
    return result.scalar_one()


async def test_new_snippet_and_recording_in_one_transaction(db):
    owner, canvas = await make_owner(db)
    snippet, row = await recording_service.create_snippet_with_recording(
        db, canvas, owner, position_x=10.0, position_y=-20.0, lines=["a cat", "sat on the mat"], recording=take()
    )
    assert snippet.snippet_type == SnippetType.freestyle
    assert (snippet.position_x, snippet.position_y) == (10.0, -20.0)
    assert snippet.raw_text == "a cat\nsat on the mat"
    assert snippet.grid_data == syllabify_text("a cat\nsat on the mat")
    assert (snippet.width, snippet.height) == compute_size(snippet.grid_data)
    assert len(snippet.phonetics["lines"]) == 2
    assert snippet.tags == []
    assert row.snippet_id == snippet.id and row.created_at is not None
    assert row.byte_size == len(b"OggS-audio")
    stored = await recording_service.get_audio(db, row.id, owner)
    assert stored == recording_service.StoredAudio("audio/ogg;codecs=opus", b"OggS-audio")


async def test_create_in_a_foreign_canvas_is_refused(db):
    _, canvas = await make_owner(db)
    stranger, _ = await make_owner(db)
    with pytest.raises(CanvasNotFoundError):
        await recording_service.create_snippet_with_recording(db, canvas, stranger, position_x=0, position_y=0, lines=["x"], recording=take())


async def test_append_keeps_the_grid_verbatim_and_bumps_content_time(db):
    owner, canvas = await make_owner(db)
    snippet = await add_snippet(db, canvas, "cat\ndog\n", ARRANGED)
    before = snippet.content_updated_at
    updated, row = await recording_service.append_with_recording(
        db, canvas, owner, snippet.id, lines=["sleeping on the mat"], recording=take("sleeping on the mat")
    )
    assert updated.raw_text == "cat\ndog\nsleeping on the mat"
    assert updated.grid_data["lines"][:2] == ARRANGED["lines"]
    assert updated.grid_data["lines"][2] == syllabify_text("sleeping on the mat")["lines"][0]
    assert [line["line_index"] for line in updated.phonetics["lines"]] == [0, 1, 2]
    assert "1:0" in updated.phonetics["overrides"]  # the hand-set stress of "dog"
    assert (updated.width, updated.height) == compute_size(updated.grid_data)
    assert updated.content_updated_at > before
    assert row.snippet_id == snippet.id
    assert await recording_count(db, snippet.id) == 1


async def test_append_checks_owner_and_canvas(db):
    owner, canvas = await make_owner(db)
    _, other_canvas = await make_owner(db)
    snippet = await add_snippet(db, canvas, "cat")
    with pytest.raises(SnippetNotFoundError):
        await recording_service.append_with_recording(db, other_canvas, owner, snippet.id, lines=["x"], recording=take())
    stranger, _ = await make_owner(db)
    with pytest.raises(SnippetNotFoundError):
        await recording_service.append_with_recording(db, canvas, stranger, snippet.id, lines=["x"], recording=take())
    assert await recording_count(db, snippet.id) == 0
    assert await recording_service.snippet_in_canvas(db, snippet.id, canvas, owner)
    assert not await recording_service.snippet_in_canvas(db, snippet.id, other_canvas, owner)


async def test_list_newest_first_without_loading_audio(db):
    owner, canvas = await make_owner(db)
    snippet = await add_snippet(db, canvas, "cat")
    _, first = await recording_service.append_with_recording(db, canvas, owner, snippet.id, lines=["one"], recording=take("one"))
    _, second = await recording_service.append_with_recording(db, canvas, owner, snippet.id, lines=["two"], recording=take("two"))
    db.expunge_all()
    rows = await recording_service.list_recordings(db, snippet.id, owner)
    assert [r.id for r in rows] == [second.id, first.id]
    assert all("audio" not in r.__dict__ for r in rows)
    stranger, _ = await make_owner(db)
    with pytest.raises(SnippetNotFoundError):
        await recording_service.list_recordings(db, snippet.id, stranger)


async def test_audio_and_delete_are_owner_only(db):
    owner, canvas = await make_owner(db)
    stranger, _ = await make_owner(db)
    snippet = await add_snippet(db, canvas, "cat")
    _, row = await recording_service.append_with_recording(db, canvas, owner, snippet.id, lines=["one"], recording=take())
    with pytest.raises(RecordingNotFoundError):
        await recording_service.get_audio(db, row.id, stranger)
    with pytest.raises(RecordingNotFoundError):
        await recording_service.delete_recording(db, row.id, stranger)
    await recording_service.delete_recording(db, row.id, owner)
    with pytest.raises(RecordingNotFoundError):
        await recording_service.delete_recording(db, row.id, owner)
    assert await recording_count(db, snippet.id) == 0


async def test_deleting_a_snippet_or_canvas_cascades_to_recordings(db):
    owner, canvas = await make_owner(db)
    a = await add_snippet(db, canvas, "a")
    b = await add_snippet(db, canvas, "b")
    await recording_service.append_with_recording(db, canvas, owner, a.id, lines=["x"], recording=take())
    await recording_service.append_with_recording(db, canvas, owner, b.id, lines=["y"], recording=take())
    assert await snippet_service.delete_snippet(db, str(a.id), str(owner))
    assert await recording_count(db, a.id) == 0
    assert await canvas_service.delete_canvas(db, str(canvas), str(owner))
    assert await recording_count(db, b.id) == 0


async def test_merge_moves_recordings_to_the_merged_snippet(db):
    owner, canvas = await make_owner(db)
    a = await add_snippet(db, canvas, "a")
    b = await add_snippet(db, canvas, "b")
    _, ra = await recording_service.append_with_recording(db, canvas, owner, a.id, lines=["x"], recording=take())
    _, rb = await recording_service.append_with_recording(db, canvas, owner, b.id, lines=["y"], recording=take())
    originals = await snippet_service.get_canvas_snippets(db, canvas, str(owner), [a.id, b.id])
    merged = await snippet_service.replace_with_merged(
        db, canvas, {"raw_text": "a\nx\nb\ny", "grid_data": {"lines": []}}, [], originals,
        reparent=recording_service.reassign_recordings,
    )
    rows = await recording_service.list_recordings(db, merged.id, owner)
    assert {r.id for r in rows} == {ra.id, rb.id}
    remaining = await db.execute(select(Snippet.id).where(Snippet.id.in_([a.id, b.id])))
    assert remaining.all() == []
