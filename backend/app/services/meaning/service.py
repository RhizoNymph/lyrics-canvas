"""Meaning similarity for "Rhyme, then meaning" (docs/features/rhyme_view.md#rhyme-then-meaning).

``MeaningService.similarities(target, texts)`` gives the cosine between a target (the mean of the
normalized embeddings of its parts) and each text. A text's row comes from, in order:

1. the in-process LRU (``cache.VectorLRU``),
2. the related-word vocabulary matrix when it is built for the same model (dictionary words, no
   encoding at all; ``VocabularyVectors``),
3. the embedding model, for the remaining texts in one batched call in a worker thread
   (``asyncio.to_thread``); those rows go into the LRU.

The model is loaded on first use, once, in a worker thread under an ``asyncio.Lock``. When it
cannot be loaded the service is unavailable for the rest of the process (one warning) and every
call answers None, which the endpoints turn into today's order with ``order_applied: "rhyme"``.
A failing batch answers None for that call only. State and cache are changed on the event loop
only.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Protocol

import numpy as np

from app.services.meaning.cache import VectorLRU
from app.services.meaning.embedder import Embedder, EmbedderFactory, normalized_rows
from app.services.meaning.errors import EmbedderUnavailableError, EmbeddingFailedError

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class TextPart:
    """A target part to embed: a text anchor's text, or a snippet's display text."""

    text: str


@dataclass(frozen=True, slots=True)
class VectorPart:
    """A target part with a stored embedding (a current ``snippet_embeddings`` row)."""

    vector: np.ndarray


TargetPart = TextPart | VectorPart


class VocabularyVectors(Protocol):
    """Precomputed normalized rows for some words (the related-word index); non-blocking."""

    def vocabulary_vectors(self, words: Sequence[str], model_name: str) -> Mapping[str, np.ndarray]: ...


@dataclass(frozen=True, slots=True)
class _Unloaded:
    pass


@dataclass(frozen=True, slots=True)
class _Loaded:
    embedder: Embedder


@dataclass(frozen=True, slots=True)
class _Unavailable:
    reason: str


_State = _Unloaded | _Loaded | _Unavailable


class MeaningService:
    def __init__(
        self,
        factory: EmbedderFactory,
        *,
        cache: VectorLRU | None = None,
        vocabulary: VocabularyVectors | None = None,
    ) -> None:
        self._factory = factory
        self._cache = cache if cache is not None else VectorLRU()
        self._vocabulary = vocabulary
        self._state: _State = _Unloaded()
        self._load_lock = asyncio.Lock()

    @property
    def cache(self) -> VectorLRU:
        return self._cache

    @property
    def unavailable(self) -> bool:
        return isinstance(self._state, _Unavailable)

    async def _embedder(self) -> Embedder | None:
        async with self._load_lock:
            if isinstance(self._state, _Unloaded):
                try:
                    self._state = _Loaded(await asyncio.to_thread(self._factory))
                except EmbedderUnavailableError as exc:
                    self._state = _Unavailable(exc.message)
                    logger.warning("meaning.unavailable reason=%s", exc.message)
        match self._state:
            case _Loaded(embedder=embedder):
                return embedder
            case _:
                return None

    async def embed(self, texts: Sequence[str]) -> dict[str, np.ndarray] | None:
        """Normalized rows for the distinct ``texts``; None when the model is unavailable or failed."""
        embedder = await self._embedder()
        if embedder is None:
            return None
        wanted = list(dict.fromkeys(texts))
        rows = self._cache.get_many(wanted)
        cache_hits = len(rows)
        missing = [t for t in wanted if t not in rows]
        vocabulary_hits = 0
        if missing and self._vocabulary is not None:
            known = self._vocabulary.vocabulary_vectors(missing, embedder.model_name)
            vocabulary_hits = len(known)
            rows.update(known)
            missing = [t for t in missing if t not in known]
        encode_ms = 0.0
        if missing:
            started = time.perf_counter()
            try:
                encoded = await asyncio.to_thread(_encode, embedder, missing)
            except EmbeddingFailedError as exc:
                logger.warning("meaning.embed_failed texts=%d reason=%s", len(missing), exc.message)
                return None
            encode_ms = (time.perf_counter() - started) * 1000
            fresh = dict(zip(missing, encoded))
            self._cache.put_many(fresh)
            rows.update(fresh)
        logger.debug(
            "meaning.embed texts=%d cache_hits=%d vocabulary_hits=%d encoded=%d encode_ms=%.1f",
            len(wanted), cache_hits, vocabulary_hits, len(missing), encode_ms,
        )
        return rows

    async def similarities(self, target: Sequence[TargetPart], texts: Sequence[str]) -> list[float] | None:
        """Cosine of each text against the mean of the target parts (texts order); None when unavailable."""
        if not target:
            raise ValueError("a meaning target needs at least one part")
        part_texts = [p.text for p in target if isinstance(p, TextPart)]
        rows = await self.embed([*part_texts, *texts])
        if rows is None:
            return None
        vectors = [rows[p.text] if isinstance(p, TextPart) else _unit(p.vector) for p in target]
        centre = _unit(np.mean(np.stack(vectors), axis=0))
        if not texts:
            return []
        matrix = np.stack([rows[t] for t in texts])
        return [float(s) for s in matrix @ centre]


def _encode(embedder: Embedder, texts: list[str]) -> np.ndarray:
    return normalized_rows(embedder.encode(texts), len(texts))


def _unit(vector: np.ndarray) -> np.ndarray:
    row = np.asarray(vector, dtype=np.float32).reshape(-1)
    norm = float(np.linalg.norm(row))
    return row / norm if norm > 0 else row
