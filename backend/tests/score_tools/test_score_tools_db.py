"""Draft and export against real Postgres (migration 012 columns, recordings with timings).

Skipped unless TAG_TEST_DATABASE_URL points at a throwaway database whose name contains "test"
(see tests/tags/db.py)."""

from __future__ import annotations

import uuid
import xml.etree.ElementTree as ET
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement
from app.models.recording import SnippetRecording, SplitMode
from app.models.snippet import Snippet
from app.models.user import User
from app.services.score import check_score, score_to_json
from app.services.score_tools import service
from app.services.score_tools.errors import (
    RecordingHasNoTimingsError,
    RecordingNotFoundError,
    ScoreNotFoundError,
    SnippetNotFoundError,
)
from app.services.score_tools.service import ExportFormat, GridDraft, RecordingDraft
from app.services.voice.timings import spans_json, words_json
from tests.score.helpers import grid, score
from tests.score_tools.midi_reader import parse_midi
from tests.score_tools.takes import steady
from tests.tags.db import DB_URL, alembic, requires_db

pytestmark = requires_db

TEXT = "the night is young"
GRID = grid("the ^night is ^young")
TAKE = steady(["the", "night", "is", "young"], [0, 1, 3, 4], [1, 2, 1, 4])
STORED = score_to_json(check_score(score([[(0, 0, 12, 0), (1, 12, 24, 1), (2, 36, 12, 0), (3, 48, 48, 1)]]), GRID))


@pytest.fixture(scope="module", autouse=True)
def migrated():
    alembic("downgrade", "base")
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


async def make_world(db: AsyncSession) -> SimpleNamespace:
    user = User(email=f"{uuid.uuid4()}@example.test", display_name="t")
    stranger = User(email=f"{uuid.uuid4()}@example.test", display_name="s")
    db.add_all([user, stranger])
    await db.flush()
    canvas = Canvas(owner_id=user.id, title="c")
    db.add(canvas)
    await db.flush()
    snippet = Snippet(id=uuid.uuid4(), owner_id=user.id, raw_text=TEXT, grid_data=GRID, score=STORED)
    bare = Snippet(id=uuid.uuid4(), owner_id=user.id, raw_text="no score", grid_data=grid("^no ^score"))
    foreign = Snippet(id=uuid.uuid4(), owner_id=stranger.id, raw_text=TEXT, grid_data=GRID, score=STORED)
    db.add_all([snippet, bare, foreign])
    await db.flush()
    db.add_all([SnippetPlacement(snippet_id=s.id, canvas_id=canvas.id) for s in (snippet, bare)])

    def recording(snippet_id, words, spans) -> SnippetRecording:
        return SnippetRecording(
            snippet_id=snippet_id, mime_type="audio/ogg", duration_ms=2000, byte_size=3, audio=b"abc",
            transcript=TEXT, split_mode=SplitMode.single, words=words, line_spans=spans,
        )

    timed = recording(snippet.id, words_json(TAKE), spans_json([(0, 4)]))
    untimed = recording(snippet.id, None, None)
    malformed = recording(snippet.id, [{"text": "x"}], [[0, 1]])
    elsewhere = recording(bare.id, words_json(TAKE), spans_json([(0, 4)]))
    foreign_take = recording(foreign.id, words_json(TAKE), spans_json([(0, 4)]))
    db.add_all([timed, untimed, malformed, elsewhere, foreign_take])
    await db.commit()
    return SimpleNamespace(
        user=user.id, stranger=stranger.id, snippet=snippet.id, bare=bare.id, foreign=foreign.id,
        timed=timed.id, untimed=untimed.id, malformed=malformed.id, elsewhere=elsewhere.id, foreign_take=foreign_take.id,
    )


async def test_load_snippet_checks_the_owner(db):
    w = await make_world(db)
    loaded = await service.load_snippet(db, w.snippet, w.user)
    assert (loaded.raw_text, loaded.grid, loaded.score) == (TEXT, GRID, STORED)
    with pytest.raises(SnippetNotFoundError):
        await service.load_snippet(db, w.foreign, w.user)
    with pytest.raises(SnippetNotFoundError):
        await service.load_snippet(db, uuid.uuid4(), w.user)
    assert (await service.load_snippet(db, w.bare, w.user)).score is None


async def test_load_timings(db):
    w = await make_world(db)
    timings = await service.load_timings(db, w.timed, w.snippet, w.user)
    assert [word.text for word in timings.words] == ["the", "night", "is", "young"]
    for recording_id in (w.elsewhere, w.foreign_take, uuid.uuid4()):
        with pytest.raises(RecordingNotFoundError):
            await service.load_timings(db, recording_id, w.snippet, w.user)
    with pytest.raises(RecordingNotFoundError):  # the right snippet but not the caller's
        await service.load_timings(db, w.foreign_take, w.foreign, w.user)
    for recording_id in (w.untimed, w.malformed):
        with pytest.raises(RecordingHasNoTimingsError):
            await service.load_timings(db, recording_id, w.snippet, w.user)


async def test_drafts_from_the_database(db):
    w = await make_world(db)
    from_grid = await service.draft(db, w.snippet, w.user, GridDraft({"num": 4, "den": 4}, 90, "1/16"))
    assert len(from_grid.score.all_notes()) == 4
    from_take = await service.draft(db, w.snippet, w.user, RecordingDraft(w.timed, {"num": 4, "den": 4}, None))
    assert from_take.score.pickup == 12 and from_take.unplaced == 0
    with pytest.raises(SnippetNotFoundError):
        await service.draft(db, w.foreign, w.user, GridDraft({"num": 4, "den": 4}, 90, "1/16"))


async def test_export_from_the_database(db):
    w = await make_world(db)
    file = await service.export(db, w.snippet, w.user, ExportFormat.midi)
    assert file.filename == "the-night-is-young.mid" and file.media_type == "audio/midi"
    assert len([e for e in parse_midi(file.content).tracks[0] if e.kind == "note_on"]) == 4
    with pytest.raises(ScoreNotFoundError):
        await service.export(db, w.bare, w.user, ExportFormat.musicxml)


@pytest.fixture
def world():
    import asyncio

    async def build():
        engine = create_async_engine(DB_URL, poolclass=NullPool)
        async with async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)() as session:
            built = await make_world(session)
        await engine.dispose()
        return built

    return asyncio.run(build())


@pytest.fixture
def client(world):
    async def real_db():
        engine = create_async_engine(DB_URL, poolclass=NullPool)
        async with async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)() as session:
            yield session
        await engine.dispose()

    app.dependency_overrides[get_db] = real_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=world.user)
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_routes_end_to_end(client, world):
    response = client.post(
        f"/api/snippets/{world.snippet}/score/draft",
        json={"source": "recording", "recording_id": str(world.timed), "meter": {"num": 4, "den": 4}, "bpm": 90},
    )
    assert response.status_code == 200
    assert [n["t"] for n in response.json()["score"]["lines"][0]["notes"]] == [0, 12, 36, 48]
    response = client.post(
        f"/api/snippets/{world.snippet}/score/draft",
        json={"source": "recording", "recording_id": str(world.untimed), "meter": {"num": 4, "den": 4}},
    )
    assert response.status_code == 422 and response.json()["detail"]["error"] == "recording_has_no_timings"
    response = client.get(f"/api/snippets/{world.snippet}/score/export", params={"format": "musicxml"})
    assert response.status_code == 200
    assert ET.fromstring(response.content).tag == "score-partwise"
    response = client.get(f"/api/snippets/{world.foreign}/score/export", params={"format": "midi"})
    assert response.status_code == 404 and response.json()["detail"]["error"] == "snippet_not_found"
    response = client.get(f"/api/snippets/{world.bare}/score/export", params={"format": "midi"})
    assert response.status_code == 404 and response.json()["detail"]["error"] == "score_not_found"
