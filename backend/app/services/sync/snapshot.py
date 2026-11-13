"""``GET /api/sync/snapshot`` (docs/features/sync_versions.md#snapshot): everything of one user
the offline client keeps, read in one REPEATABLE READ, READ ONLY transaction so the tables agree
with each other, one query per table whatever the data size.

Snippets come once each, with their tag ids; their layout per canvas comes as placements.
"""

from __future__ import annotations

import logging
import time
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement
from app.models.snippet import Snippet
from app.models.tag import Tag, snippet_tags
from app.models.tag_preset import TagPreset
from app.models.word_bank import BankWord
from app.services import canvas_service, tag_preset_service, tag_service
from app.services.tag_service import TagSummary
from app.services.word_bank import service as word_bank

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class SnapshotSnippet:
    snippet: Snippet
    tag_ids: tuple[uuid.UUID, ...]  # sorted by lower(tag name), then id


@dataclass(frozen=True, slots=True)
class Snapshot:
    server_time: datetime  # the transaction's clock: every row is as of this instant
    canvases: Sequence[Canvas]
    placements: Sequence[SnippetPlacement]
    snippets: Sequence[SnapshotSnippet]
    tags: Sequence[TagSummary]
    presets: Sequence[TagPreset]
    words: Sequence[BankWord]


async def _placements(db: AsyncSession, owner_id: uuid.UUID) -> list[SnippetPlacement]:
    result = await db.execute(
        select(SnippetPlacement)
        .join(Canvas, Canvas.id == SnippetPlacement.canvas_id)
        .where(Canvas.owner_id == owner_id)
        .order_by(SnippetPlacement.created_at, SnippetPlacement.id)
    )
    return list(result.scalars().all())


async def _snippets(db: AsyncSession, owner_id: uuid.UUID) -> list[SnapshotSnippet]:
    snippets = await db.execute(
        select(Snippet).where(Snippet.owner_id == owner_id).order_by(Snippet.created_at, Snippet.id)
    )
    links = await db.execute(
        select(snippet_tags.c.snippet_id, snippet_tags.c.tag_id)
        .join(Snippet, Snippet.id == snippet_tags.c.snippet_id)
        .join(Tag, Tag.id == snippet_tags.c.tag_id)
        .where(Snippet.owner_id == owner_id)
        .order_by(snippet_tags.c.snippet_id, func.lower(Tag.name), Tag.id)
    )
    tag_ids: dict[uuid.UUID, list[uuid.UUID]] = {}
    for snippet_id, tag_id in links.tuples().all():
        tag_ids.setdefault(snippet_id, []).append(tag_id)
    return [SnapshotSnippet(s, tuple(tag_ids.get(s.id, ()))) for s in snippets.scalars().all()]


async def load_snapshot(db: AsyncSession, owner_id: uuid.UUID) -> Snapshot:
    started = time.perf_counter()
    # end whatever the request already read (the current user) so the snapshot gets a transaction
    # of its own, with a single view of every table
    await db.commit()
    await db.execute(text("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY"))
    server_time = (await db.execute(select(func.now()))).scalar_one()
    snapshot = Snapshot(
        server_time=server_time,
        canvases=await canvas_service.list_canvases(db, str(owner_id)),
        placements=await _placements(db, owner_id),
        snippets=await _snippets(db, owner_id),
        tags=await tag_service.list_tags(db, owner_id),
        presets=await tag_preset_service.list_presets(db, owner_id),
        words=await word_bank.list_words(db, owner_id),
    )
    await db.commit()
    logger.info(
        "sync.snapshot user_id=%s canvases=%d placements=%d snippets=%d tags=%d words=%d elapsed_ms=%.1f",
        owner_id, len(snapshot.canvases), len(snapshot.placements), len(snapshot.snippets), len(snapshot.tags),
        len(snapshot.words), (time.perf_counter() - started) * 1000,
    )
    return snapshot
