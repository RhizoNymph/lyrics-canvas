"""Meaning targets for snippets: a stored ``snippet_embeddings`` row when it is current, else the
snippet's display text (embedded by the meaning service).

A stored row is current when it was written at or after the snippet's last content change
(``snippet_embeddings.updated_at >= snippets.content_updated_at``). Nothing is written here: the
computed vector of a snippet's text lives in the meaning service's LRU.
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

import numpy as np
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.embedding import SnippetEmbedding
from app.models.snippet import Snippet
from app.services.meaning.service import TargetPart, TextPart, VectorPart
from app.services.rest_marks import display_text


class SnippetEmbeddingStore(Protocol):
    async def current(self, snippet_ids: Sequence[str]) -> dict[str, np.ndarray]: ...


class NoStoredEmbeddings:
    """A store with no rows (tests, and callers without a session)."""

    async def current(self, snippet_ids: Sequence[str]) -> dict[str, np.ndarray]:
        return {}


class DbSnippetEmbeddingStore:
    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def current(self, snippet_ids: Sequence[str]) -> dict[str, np.ndarray]:
        ids = [u for u in (_as_uuid(s) for s in snippet_ids) if u is not None]
        if not ids:
            return {}
        result = await self._db.execute(
            select(SnippetEmbedding.snippet_id, SnippetEmbedding.embedding)
            .join(Snippet, Snippet.id == SnippetEmbedding.snippet_id)
            .where(SnippetEmbedding.snippet_id.in_(ids), SnippetEmbedding.updated_at >= Snippet.content_updated_at)
        )
        return {str(sid): np.asarray(vector, dtype=np.float32) for sid, vector in result.all()}


@dataclass(frozen=True, slots=True)
class SnippetText:
    id: str
    raw_text: str


async def snippet_parts(snippets: Sequence[SnippetText], store: SnippetEmbeddingStore) -> list[TargetPart]:
    """One part per distinct snippet with words: its current stored row, else its display text."""
    distinct = list({s.id: s for s in snippets}.values())
    texts = {s.id: display_text(s.raw_text).strip() for s in distinct}
    stored = await store.current([s.id for s in distinct if texts[s.id]])
    parts: list[TargetPart] = []
    for s in distinct:
        if s.id in stored:
            parts.append(VectorPart(stored[s.id]))
        elif texts[s.id]:
            parts.append(TextPart(texts[s.id]))
    return parts


def _as_uuid(value: str) -> uuid.UUID | None:
    try:
        return uuid.UUID(value)
    except ValueError:
        return None
