"""Rhyme patterns across lines: detect the slots where selected lines rhyme together, and suggest
words and phrases that rhyme with every member of each slot (docs/features/rhyme_patterns.md).

Entry points ``detect`` and ``suggest``; data access goes through ``PatternRepository``
(``DbPatternRepository`` in production, a fake in tests).
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from typing import Callable, Protocol

from sqlalchemy import select

from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement
from app.schemas.rhyme_pattern import (
    PatternDetectRequest,
    PatternDetectResponse,
    PatternLines,
    PatternSlotResult,
    PatternSuggestRequest,
    PatternSuggestResponse,
)
from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.index import RhymeIndex
from app.services.phonetics.pattern.detect import detect as detect_slots
from app.services.phonetics.pattern.pair import PairScorer
from app.services.phonetics.pattern.slots import summarize
from app.services.phonetics.sources.dictionary import DictionarySource
from app.services.rhyme_pattern.models import line_model, slot_model
from app.services.rhyme_pattern.resolve import PatternRange, resolve_range, resolve_slots
from app.services.rhyme_pattern.suggest import NoteSpans, SlotRequest, SuggestParams, build_note_spans, compute
from app.services.rhyme_suggest_service import (
    CanvasNotFoundError,
    DbSnippetRepository,
    SnippetNotFoundError,
    SnippetRecord,
    SnippetRepository,
    matches_filter,
    resolve_dialect,
    resolve_weights,
)
from app.services.tag_expr import parse

logger = logging.getLogger(__name__)


class PatternRepository(SnippetRepository, Protocol):
    async def snippet_on_canvas(self, snippet_id: str, canvas_id: str, user_id: str) -> bool: ...


def _as_uuid(value: str) -> uuid.UUID | None:
    try:
        return uuid.UUID(value)
    except ValueError:
        return None


class DbPatternRepository(DbSnippetRepository):
    async def snippet_on_canvas(self, snippet_id: str, canvas_id: str, user_id: str) -> bool:
        sid, cid, uid = _as_uuid(snippet_id), _as_uuid(canvas_id), _as_uuid(user_id)
        if sid is None or cid is None or uid is None:
            return False
        result = await self._db.execute(
            select(SnippetPlacement.id)
            .join(Canvas, Canvas.id == SnippetPlacement.canvas_id)
            .where(SnippetPlacement.snippet_id == sid, SnippetPlacement.canvas_id == cid, Canvas.owner_id == uid)
        )
        return result.scalar_one_or_none() is not None


DictionaryIndexFactory = Callable[[DialectMap, bool], RhymeIndex]


def default_dictionary_index(dialect: DialectMap, include_rare: bool) -> RhymeIndex:
    """The process-wide dictionary index shared with rhyme-suggest."""
    return DictionarySource(dialect, None, include_rare).index()


async def _load(req: PatternLines, user_id: str, repo: PatternRepository) -> SnippetRecord:
    if not await repo.canvas_owned(req.canvas_id, user_id):
        raise CanvasNotFoundError(f"canvas {req.canvas_id} not found")
    record = await repo.snippet(req.snippet_id, user_id)
    if record is None or not await repo.snippet_on_canvas(req.snippet_id, req.canvas_id, user_id):
        raise SnippetNotFoundError(f"snippet {req.snippet_id} not found on canvas {req.canvas_id}")
    return record


def _elapsed(started: float) -> float:
    return round((time.perf_counter() - started) * 1000, 2)


async def detect(req: PatternDetectRequest, user_id: str, repo: PatternRepository) -> PatternDetectResponse:
    started = time.perf_counter()
    record = await _load(req, user_id, repo)
    dialect = resolve_dialect(req)
    weights = resolve_weights(req)
    rng = resolve_range(record, req.line_start, req.line_end)
    slots = detect_slots(
        [line.words for line in rng.lines], dialect=dialect, weights=weights, min_score=req.min_score, snippet_id=record.id
    )
    response = PatternDetectResponse(
        lines=[line_model(line) for line in rng.lines],
        slots=[slot_model(f"s{i}", slot, rng) for i, slot in enumerate(slots)],
        elapsed_ms=_elapsed(started),
    )
    logger.info(
        "rhyme_pattern.detect snippet=%s lines=%d pattern_lines=%d min_score=%.2f slots=%d coverage=%s elapsed_ms=%.1f",
        record.id, len(rng.lines), rng.line_count, req.min_score, len(slots),
        ",".join(str(s.coverage) for s in slots), response.elapsed_ms,
    )
    return response


async def _note_spans(
    req: PatternSuggestRequest, user_id: str, repo: PatternRepository, rng: PatternRange, dialect: DialectMap
) -> NoteSpans | None:
    if "notes" not in req.sources and not req.line_matches:
        return None
    tag_filter = parse(req.tag_filter) if req.tag_filter else None
    records = [r for r in await repo.user_snippets(user_id) if matches_filter(r, tag_filter)]
    skip = frozenset((rng.record.id, line.line_index) for line in rng.lines)
    return await asyncio.to_thread(build_note_spans, records, dialect, skip)


async def suggest(
    req: PatternSuggestRequest,
    user_id: str,
    repo: PatternRepository,
    *,
    dictionary: DictionaryIndexFactory | None = None,
) -> PatternSuggestResponse:
    started = time.perf_counter()
    # an invalid filter is a request error before anything is looked up (TagExpressionError)
    if req.tag_filter:
        parse(req.tag_filter)
    record = await _load(req, user_id, repo)
    dialect = resolve_dialect(req)
    weights = resolve_weights(req)
    rng = resolve_range(record, req.line_start, req.line_end)
    resolved = resolve_slots(rng, req.slots, dialect)
    scorer = PairScorer(0.0, weights)
    slots = [SlotRequest(s.id, summarize(s.members, scorer, rng.line_count, s.k)) for s in resolved]
    notes = await _note_spans(req, user_id, repo, rng, dialect)
    factory = dictionary or default_dictionary_index
    params = SuggestParams(
        snippet_id=record.id,
        sources=frozenset(req.sources),
        min_score=req.min_score,
        exclude=frozenset(req.exclude_ids),
        weights=weights,
        limit=req.limit,
        offsets=dict(req.offsets),
        line_matches=req.line_matches,
        line_match_limit=req.line_match_limit,
        line_match_offset=req.line_match_offset,
    )

    def run():
        index = factory(dialect, req.include_rare) if "dictionary" in req.sources else None
        return compute(slots, params, dialect, notes, index)

    result = await asyncio.to_thread(run)
    response = PatternSuggestResponse(
        slots=[
            PatternSlotResult(slot=slot_model(s.id, s.slot, rng), candidates=page.candidates, total=page.total)
            for s, page in zip(slots, result.pages)
        ],
        line_matches=result.line_matches,
        line_match_total=result.line_match_total,
        elapsed_ms=_elapsed(started),
    )
    logger.info(
        "rhyme_pattern.suggest snippet=%s lines=%d slots=%d candidates=%s line_matches=%d sources=%s tag_filter=%s elapsed_ms=%.1f",
        record.id, rng.line_count, len(slots), ",".join(str(p.total) for p in result.pages), result.line_match_total,
        ",".join(req.sources), req.tag_filter is not None and bool(req.tag_filter.strip()), response.elapsed_ms,
    )
    return response
