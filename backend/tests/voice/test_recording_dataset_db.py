"""Dataset persistence against a real, throwaway PostgreSQL database: timings stored on create and
append, verification, stats, export selection and the hint-word read.

Skipped unless VOICE_TEST_DATABASE_URL points at a disposable database whose name contains "test"
(see tests/voice/test_recordings_db.py)."""

from __future__ import annotations

import os
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

import pytest
from sqlalchemy import select, text
from sqlalchemy.engine import make_url
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.models.canvas import Canvas
from app.models.recording import SnippetRecording, SplitMode
from app.models.snippet import Snippet
from app.models.user import User
from app.models.word_bank import BankWord
from app.services import recording_service
from app.services.snippet_content import derive_new_content
from app.services.voice.errors import ExportTooLargeError, RecordingNotFoundError
from app.services.voice.line_assembly import TimedWord
from app.services.voice.timings import Timings
from app.services.voice.verification import VerifiedText

DB_URL = os.environ.get("VOICE_TEST_DATABASE_URL", "")
BACKEND = Path(__file__).resolve().parents[2]

pytestmark = pytest.mark.skipif(
    not DB_URL or "test" not in (make_url(DB_URL).database or ""),
    reason="set VOICE_TEST_DATABASE_URL to a throwaway database whose name contains 'test'",
)

WORDS = (TimedWord("a", 0, 300, 0.9), TimedWord("b", 400, 700, None), TimedWord("c", 800, 900, 0.4))
TIMINGS = Timings(WORDS, ((0, 2), (2, 3)))
HUGE = 10**12


def alembic(*args: str) -> None:
    env = {**os.environ, "DATABASE_URL": DB_URL}
    subprocess.run([sys.executable, "-m", "alembic", *args], cwd=BACKEND, env=env, check=True, capture_output=True)


@pytest.fixture(scope="module", autouse=True)
def migrated():
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


async def make_owner(db: AsyncSession) -> tuple[uuid.UUID, uuid.UUID, uuid.UUID]:
    user = User(email=f"{uuid.uuid4()}@example.test", display_name="t")
    db.add(user)
    await db.flush()
    canvas = Canvas(owner_id=user.id, title="c")
    db.add(canvas)
    await db.flush()
    content = derive_new_content("cat")
    snippet = Snippet(canvas_id=canvas.id, raw_text="cat", grid_data=content.grid_data, phonetics=content.phonetics,
                      width=content.width, height=content.height)
    db.add(snippet)
    await db.commit()
    return user.id, canvas.id, snippet.id


def take(transcript: str = "a b\nc", *, timings: Timings | None = TIMINGS, audio: bytes = b"OggS", duration: int = 1000,
         prompt: str | None = "Vocabulary: zephyr.") -> recording_service.NewRecording:
    return recording_service.NewRecording(
        mime_type="audio/ogg", duration_ms=duration, audio=audio, transcript=transcript, split_mode=SplitMode.taps,
        timings=timings, language="en", initial_prompt=prompt,
    )


async def append(db, owner, canvas, snippet, **kwargs) -> uuid.UUID:
    transcript = kwargs.pop("transcript", "a b\nc")
    _, row = await recording_service.append_with_recording(
        db, canvas, owner, snippet, lines=transcript.split("\n"), recording=take(transcript, **kwargs)
    )
    return row.id


async def test_create_stores_words_spans_language_and_prompt(db):
    owner, canvas, _ = await make_owner(db)
    _, row = await recording_service.create_snippet_with_recording(
        db, canvas, owner, position_x=0, position_y=0, lines=["a b", "c"], recording=take()
    )
    db.expunge_all()
    stored = await recording_service.get_recording(db, row.id, owner)
    assert stored.words == [
        {"text": "a", "start_ms": 0, "end_ms": 300, "probability": 0.9},
        {"text": "b", "start_ms": 400, "end_ms": 700, "probability": None},
        {"text": "c", "start_ms": 800, "end_ms": 900, "probability": 0.4},
    ]
    assert stored.line_spans == [[0, 2], [2, 3]]
    assert stored.language == "en"
    assert stored.initial_prompt == "Vocabulary: zephyr."
    assert stored.verified_text is None and stored.verified_at is None
    assert recording_service.timings_of(stored) == TIMINGS
    assert "audio" not in stored.__dict__


async def test_append_stores_timings_and_null_prompt_as_sql_null(db):
    owner, canvas, snippet = await make_owner(db)
    rid = await append(db, owner, canvas, snippet, prompt=None, timings=None, transcript="x")
    result = await db.execute(
        text("SELECT words IS NULL, line_spans IS NULL, initial_prompt IS NULL FROM snippet_recordings WHERE id = :id"),
        {"id": rid},
    )
    assert tuple(result.one()) == (True, True, True)


async def test_verification_set_and_clear(db):
    owner, canvas, snippet = await make_owner(db)
    rid = await append(db, owner, canvas, snippet)
    before = datetime.now(timezone.utc)
    row = await recording_service.set_verification(db, rid, owner, VerifiedText("a bee\nsee"))
    assert row.verified_text == "a bee\nsee"
    assert row.verified_at is not None and row.verified_at.tzinfo is not None
    first = row.verified_at
    assert abs((first - before).total_seconds()) < 60
    again = await recording_service.set_verification(db, rid, owner, VerifiedText("x"))
    assert again.verified_text == "x" and again.verified_at >= first
    db.expunge_all()
    stored = await recording_service.get_recording(db, rid, owner)
    assert stored.transcript == "a b\nc"  # the Whisper text is untouched
    cleared = await recording_service.clear_verification(db, rid, owner)
    assert cleared.verified_text is None and cleared.verified_at is None
    cleared = await recording_service.clear_verification(db, rid, owner)  # idempotent
    db.expunge_all()
    stored = await recording_service.get_recording(db, rid, owner)
    assert stored.verified_text is None and stored.verified_at is None


async def test_verification_is_owner_only(db):
    owner, canvas, snippet = await make_owner(db)
    stranger, _, _ = await make_owner(db)
    rid = await append(db, owner, canvas, snippet)
    with pytest.raises(RecordingNotFoundError):
        await recording_service.set_verification(db, rid, stranger, VerifiedText("x"))
    with pytest.raises(RecordingNotFoundError):
        await recording_service.clear_verification(db, rid, stranger)
    with pytest.raises(RecordingNotFoundError):
        await recording_service.get_recording(db, rid, stranger)
    with pytest.raises(RecordingNotFoundError):
        await recording_service.set_verification(db, uuid.uuid4(), owner, VerifiedText("x"))


async def test_stats_cover_all_canvases_of_the_user(db):
    owner, canvas, snippet = await make_owner(db)
    other_canvas = Canvas(owner_id=owner, title="second")
    db.add(other_canvas)
    await db.commit()
    content = derive_new_content("dog")
    second = Snippet(canvas_id=other_canvas.id, raw_text="dog", grid_data=content.grid_data, phonetics=content.phonetics,
                     width=content.width, height=content.height)
    db.add(second)
    await db.commit()
    a = await append(db, owner, canvas, snippet, duration=1000)
    await append(db, owner, canvas, snippet, duration=2000, timings=None, transcript="x")
    await append(db, owner, other_canvas.id, second.id, duration=500)
    await recording_service.set_verification(db, a, owner, VerifiedText("ok"))
    stranger, s_canvas, s_snippet = await make_owner(db)
    await append(db, stranger, s_canvas, s_snippet, duration=9999)

    stats = await recording_service.recording_stats(db, owner)
    assert stats == recording_service.RecordingStats(
        total=3, verified=1, total_duration_ms=3500, verified_duration_ms=1000, with_timings=2
    )
    fresh, _, _ = await make_owner(db)
    assert await recording_service.recording_stats(db, fresh) == recording_service.RecordingStats(0, 0, 0, 0, 0)


async def test_export_selection_order_and_limit(db):
    owner, canvas, snippet = await make_owner(db)
    first = await append(db, owner, canvas, snippet, audio=b"1234")
    second = await append(db, owner, canvas, snippet, audio=b"56", timings=None, transcript="x")
    third = await append(db, owner, canvas, snippet, audio=b"789")
    await recording_service.set_verification(db, third, owner, VerifiedText("checked"))
    await recording_service.set_verification(db, first, owner, VerifiedText("checked"))
    stranger, s_canvas, s_snippet = await make_owner(db)
    foreign = await append(db, stranger, s_canvas, s_snippet)
    await recording_service.set_verification(db, foreign, stranger, VerifiedText("theirs"))

    verified = await recording_service.export_recordings(db, owner, include_unverified=False, max_audio_bytes=HUGE)
    assert [r.id for r in verified] == [first, third]
    assert verified[0].timings == TIMINGS and verified[0].verified_text == "checked"
    everything = await recording_service.export_recordings(db, owner, include_unverified=True, max_audio_bytes=HUGE)
    assert [r.id for r in everything] == [first, second, third]
    assert everything[1].timings is None and everything[1].verified_text is None

    assert len(await recording_service.export_recordings(db, owner, include_unverified=True, max_audio_bytes=9)) == 3
    with pytest.raises(ExportTooLargeError):
        await recording_service.export_recordings(db, owner, include_unverified=True, max_audio_bytes=8)
    assert len(await recording_service.export_recordings(db, owner, include_unverified=False, max_audio_bytes=7)) == 2


async def test_check_constraint_is_in_the_model_and_db(db):
    owner, canvas, snippet = await make_owner(db)
    rid = await append(db, owner, canvas, snippet)
    row = (await db.execute(select(SnippetRecording).where(SnippetRecording.id == rid))).scalar_one()
    row.verified_text = "only text"
    with pytest.raises(Exception) as info:
        await db.commit()
    assert "ck_snippet_recordings_verified_pair" in str(info.value)
    await db.rollback()


async def test_bank_hint_words_are_the_users_own(db):
    owner, _, _ = await make_owner(db)
    stranger, _, _ = await make_owner(db)
    db.add_all([BankWord(user_id=owner, text="Zephyr"), BankWord(user_id=owner, text="midnight train"),
                BankWord(user_id=stranger, text="theirs")])
    await db.commit()
    words = await recording_service.bank_hint_words(db, owner)
    assert sorted(w.text for w in words) == ["Zephyr", "midnight train"]
    assert all(w.created_at.tzinfo is not None for w in words)
