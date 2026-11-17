"""DbSnippetEmbeddingStore against Postgres: only rows written at or after the snippet's last
content change are current (TAG_TEST_DATABASE_URL, see tests/tags/db.py)."""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

import numpy as np
from sqlalchemy import text, update

from app.models.embedding import SnippetEmbedding
from app.models.snippet import Snippet
from app.services.meaning.service import TextPart, VectorPart
from app.services.meaning.store import DbSnippetEmbeddingStore, SnippetText, snippet_parts
from tests.placements.db import make_world, place_new
from tests.tags.db import requires_db

pytestmark = requires_db

T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


def _vector(axis: int) -> list[float]:
    row = [0.0] * 384
    row[axis] = 1.0
    return row


async def _embedding(db, snippet_id: uuid.UUID, axis: int, written: datetime, edited: datetime) -> None:
    db.add(SnippetEmbedding(snippet_id=snippet_id, embedding=_vector(axis)))
    await db.flush()
    await db.execute(update(SnippetEmbedding).where(SnippetEmbedding.snippet_id == snippet_id).values(updated_at=written))
    await db.execute(update(Snippet).where(Snippet.id == snippet_id).values(content_updated_at=edited))


async def _vector_column(db) -> None:
    """Migration 001 creates the column as bytea ("pgvector type in prod"); the model maps it as
    vector(384), which is what this store reads, so the test gives the column the model's type."""
    await db.execute(text("CREATE EXTENSION IF NOT EXISTS vector"))
    await db.execute(text("ALTER TABLE snippet_embeddings ALTER COLUMN embedding TYPE vector(384) USING NULL"))
    await db.commit()


async def test_current_rows_only(db):
    await _vector_column(db)
    world = await make_world(db)
    fresh = await place_new(db, world.song, world.owner, "fresh line")
    stale = await place_new(db, world.song, world.owner, "stale line")
    bare = await place_new(db, world.song, world.owner, "no row")
    same = await place_new(db, world.song, world.owner, "same instant")
    await _embedding(db, fresh.snippet.id, 1, written=T0 + timedelta(minutes=5), edited=T0)
    await _embedding(db, stale.snippet.id, 2, written=T0, edited=T0 + timedelta(minutes=5))
    await _embedding(db, same.snippet.id, 3, written=T0, edited=T0)
    await db.commit()

    store = DbSnippetEmbeddingStore(db)
    ids = [str(p.snippet.id) for p in (fresh, stale, bare, same)] + ["not-a-uuid"]
    rows = await store.current(ids)
    assert set(rows) == {str(fresh.snippet.id), str(same.snippet.id)}
    assert rows[str(fresh.snippet.id)].dtype == np.float32
    assert int(np.argmax(rows[str(fresh.snippet.id)])) == 1
    assert await store.current([]) == {}

    parts = await snippet_parts(
        [SnippetText(str(fresh.snippet.id), "fresh line"), SnippetText(str(stale.snippet.id), "stale line")], store
    )
    assert isinstance(parts[0], VectorPart) and parts[1] == TextPart("stale line")
