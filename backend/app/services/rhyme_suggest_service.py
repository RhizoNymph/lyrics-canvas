"""Rhyme suggestions: resolve anchors, run sources, merge, rank, slot-check, page."""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Callable, Mapping, Protocol, Sequence

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.canvas import Canvas
from app.models.snippet import Snippet, SnippetType
from app.schemas.phonetics import SnippetPhonetics
from app.schemas.rhyme import (
    Anchor,
    DialectMapModel,
    RhymeGridCellModel,
    RhymeGridLineModel,
    RhymeCandidateModel,
    RhymeSuggestRequest,
    RhymeSuggestCandidateModel,
    RhymeSuggestResponse,
    RhymeSyllable,
    RhymeUnitModel,
    ScoreWeightsModel,
    Slot,
    SnippetAnchor,
    SyllableAlignmentModel,
    TextAnchor,
)
from app.services.meaning.order import apply_order
from app.services.meaning.service import MeaningService, TargetPart, TextPart
from app.services.meaning.store import NoStoredEmbeddings, SnippetEmbeddingStore, SnippetText, snippet_parts
from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics import DEFAULT_DIALECT_MAP
from app.services.phonetics.dialect import DialectMap, DialectRule, RuleKind
from app.services.phonetics.errors import PronunciationError, RhymeQueryError
from app.services.phonetics.score import ScoreWeights
from app.services.phonetics.sources.base import Candidate, CandidateSource
from app.services.phonetics.sources.dictionary import DictionarySource
from app.services.phonetics.sources.notes import NotesSource
from app.services.phonetics.types import Unit
from app.services.phonetics.units import unit_from_text
from app.services.phonetics_service import compute_snippet_phonetics, note_unit
from app.services.rest_marks import display_text, word_tokens
from app.services.rhyme_order import Ordered, band_of
from app.services.rhythm_fit import (
    RhythmUnavailable,
    SlotRhythm,
    candidate_stresses,
    order_by_rhythm,
    rhythm_fit,
    stored_slot_rhythm,
)
from app.services.tag_expr import Expr, SnippetFacts, matches, parse

logger = logging.getLogger(__name__)

SOURCE_LIMIT = 5000  # per-source cap; ``total`` counts up to this many per source


class RhymeSuggestError(Exception):
    """Base class for suggest-service errors that are not engine errors."""


class CanvasNotFoundError(RhymeSuggestError):
    pass


class SnippetNotFoundError(RhymeSuggestError):
    pass


@dataclass(frozen=True, slots=True)
class SnippetRecord:
    """One snippet the user owns: content only (a snippet can be on several canvases)."""

    id: str
    owner_id: str
    raw_text: str
    grid_data: dict | None
    phonetics: dict | None
    tag_ids: tuple[str, ...]
    snippet_type: SnippetType = SnippetType.freestyle
    tag_names: tuple[str, ...] = ()
    score: dict | None = None  # the snippet's stored score JSON (null = no score)


class SnippetRepository(Protocol):
    async def canvas_owned(self, canvas_id: str, user_id: str) -> bool: ...

    async def snippet(self, snippet_id: str, user_id: str) -> SnippetRecord | None: ...

    async def user_snippets(self, user_id: str) -> list[SnippetRecord]: ...


def _as_uuid(value: str) -> uuid.UUID | None:
    try:
        return uuid.UUID(value)
    except ValueError:
        return None


def _record(snippet: Snippet) -> SnippetRecord:
    return SnippetRecord(
        id=str(snippet.id),
        owner_id=str(snippet.owner_id),
        raw_text=snippet.raw_text or "",
        grid_data=snippet.grid_data,
        phonetics=snippet.phonetics,
        tag_ids=tuple(str(t.id) for t in (snippet.tags or [])),
        snippet_type=snippet.snippet_type,
        tag_names=tuple(t.name for t in (snippet.tags or [])),
    )


class DbSnippetRepository:
    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def canvas_owned(self, canvas_id: str, user_id: str) -> bool:
        cid, uid = _as_uuid(canvas_id), _as_uuid(user_id)
        if cid is None or uid is None:
            return False
        result = await self._db.execute(select(Canvas.id).where(Canvas.id == cid, Canvas.owner_id == uid))
        return result.scalar_one_or_none() is not None

    async def snippet(self, snippet_id: str, user_id: str) -> SnippetRecord | None:
        sid, uid = _as_uuid(snippet_id), _as_uuid(user_id)
        if sid is None or uid is None:
            return None
        result = await self._db.execute(
            select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == sid, Snippet.owner_id == uid)
        )
        snippet = result.scalar_one_or_none()
        return _record(snippet) if snippet is not None else None

    async def user_snippets(self, user_id: str) -> list[SnippetRecord]:
        uid = _as_uuid(user_id)
        if uid is None:
            return []
        result = await self._db.execute(
            select(Snippet).options(selectinload(Snippet.tags))
            .where(Snippet.owner_id == uid, Snippet.raw_text != "")
            .order_by(Snippet.created_at, Snippet.id)
        )
        # one row per snippet (owner_id, not placements), so a snippet on several canvases counts once
        return [_record(s) for s in result.scalars().all()]


# --- pure helpers ---


class PhoneticsOptions(Protocol):
    """Request fields shared by rhyme-suggest and rhyme-pattern: dialect map and score weights."""

    @property
    def dialect(self) -> DialectMapModel | None: ...

    @property
    def weights(self) -> ScoreWeightsModel | None: ...


def resolve_dialect(req: PhoneticsOptions) -> DialectMap:
    if req.dialect is None:
        return DEFAULT_DIALECT_MAP
    return DialectMap(
        rules=tuple(DialectRule(RuleKind(r.kind), tuple(r.source), tuple(r.target)) for r in req.dialect.rules)
    )


def resolve_weights(req: PhoneticsOptions) -> ScoreWeights | None:
    if req.weights is None:
        return None
    return ScoreWeights(vowel=req.weights.vowel, coda=req.weights.coda, onset=req.weights.onset)


def fits_slot(stress: str, slot: Slot | None) -> bool:
    """True when the stress string has the slot's length and no stressed syllable lands on a slot 0."""
    if slot is None:
        return True
    if len(stress) != len(slot.stress):
        return False
    return not any(c in "12" and s == "0" for c, s in zip(stress, slot.stress))


def snippet_phonetics(record: SnippetRecord) -> SnippetPhonetics:
    if record.phonetics is not None:
        return SnippetPhonetics.model_validate(record.phonetics)
    return compute_snippet_phonetics(record.raw_text, record.grid_data, None)


def matches_filter(record: SnippetRecord, tag_filter: Expr | None) -> bool:
    """True when the snippet's type and tag names match the expression (None matches all)."""
    return matches(tag_filter, SnippetFacts.of(record.snippet_type, record.tag_names))


def _source_rank(source: str) -> int:
    match source:
        case "notes":
            return 0
        case _:
            return 1


def rank(candidates: Sequence[Candidate], context_tags: frozenset[str]) -> list[Candidate]:
    """Best anchor score, match depth, notes before dictionary, shared context tags, id."""
    return sorted(
        candidates,
        key=lambda c: (
            -c.score.score,
            -c.score.match_depth,
            _source_rank(c.source),
            -len(context_tags.intersection(c.unit.tags)),
            c.unit.id,
        ),
    )


def dedupe_note_suffixes(candidates: Sequence[Candidate], slot: Slot | None) -> list[Candidate]:
    """Keep one suffix per note line: best score, then depth, then fits the slot, then the longest."""
    best: dict[tuple[str, int], Candidate] = {}
    others: list[Candidate] = []
    for c in candidates:
        if c.unit.snippet_id is None or c.unit.line_index is None or c.unit.word_offset is None:
            others.append(c)
            continue
        key = (c.unit.snippet_id, c.unit.line_index)
        current = best.get(key)
        if current is None or _suffix_key(c, slot) < _suffix_key(current, slot):
            best[key] = c
    return others + list(best.values())


def _suffix_key(c: Candidate, slot: Slot | None) -> tuple[float, int, int, int]:
    return (-c.score.score, -c.score.match_depth, 0 if fits_slot(c.unit.stress, slot) else 1, c.unit.word_offset or 0)


def slice_grid_line(line: Mapping[str, object], offset: int, end: int | None) -> RhymeGridLineModel:
    """Cells of ``line`` whose word index falls in ``[offset, end)``, re-based to the range.

    Leading rests are dropped; interior rests are kept; trailing rests are kept only for an
    open-ended slice (``end`` None), so a range's strip ends on its last word.
    """
    cells: list[RhymeGridCellModel] = []
    for cell in line.get("cells", []):  # type: ignore[union-attr]
        word_index = cell.get("word_index")
        if cell.get("rest"):
            if cells:
                cells.append(RhymeGridCellModel.model_validate(cell))
            continue
        if isinstance(word_index, int) and word_index >= offset and (end is None or word_index < end):
            cells.append(RhymeGridCellModel.model_validate({**cell, "word_index": word_index - offset}))
    if end is not None:
        while cells and cells[-1].rest:
            cells.pop()
    raw_text = " ".join(word_tokens(str(line.get("raw_text", "")))[offset:end])
    return RhymeGridLineModel(cells=cells, syllable_count=sum(1 for c in cells if not c.rest), raw_text=raw_text)


def grid_line_for(unit: Unit, grid_data: Mapping[str, object] | None) -> RhymeGridLineModel:
    """The unit's beat-grid line: sliced from the snippet grid when available, else syllabified."""
    if unit.line_index is not None and grid_data:
        lines = grid_data.get("lines") or []  # type: ignore[union-attr]
        if unit.line_index < len(lines):
            return slice_grid_line(lines[unit.line_index], unit.word_offset or 0, unit.word_end)
    lines = syllabify_text(unit.text)["lines"]
    if lines:
        return RhymeGridLineModel.model_validate(lines[0])
    return RhymeGridLineModel(cells=[], syllable_count=0, raw_text=unit.text)


def unit_model(unit: Unit, grid_data: Mapping[str, object] | None = None) -> RhymeUnitModel:
    return RhymeUnitModel(
        id=unit.id,
        text=unit.text,
        provenance=unit.provenance.value,
        ephemeral=unit.ephemeral,
        syllable_count=unit.syllable_count,
        stress=unit.stress,
        rhyme_key=list(unit.rhyme_key),
        syllables=[
            RhymeSyllable(onset=list(s.onset), nucleus=s.nucleus, coda=list(s.coda), stress=s.stress)
            for s in unit.effective_pronunciation.syllables
        ],
        grid_line=grid_line_for(unit, grid_data),
        snippet_id=unit.snippet_id,
        line_index=unit.line_index,
        word_offset=unit.word_offset,
        word_end=unit.word_end,
        tags=list(unit.tags),
    )


def _candidate_fields(
    c: Candidate, slot: Slot | None, grid_data: Mapping[str, object] | None, similarity: float | None
) -> dict[str, object]:
    base = unit_model(c.unit, grid_data)
    return dict(
        **base.model_dump(),
        score=c.score.score,
        match_depth=c.score.match_depth,
        best_anchor_id=c.best_anchor_id,
        fits_slot=fits_slot(c.unit.stress, slot),
        alignment=[
            SyllableAlignmentModel(
                cand_index=a.b_index, anchor_index=a.a_index, nucleus=a.nucleus, coda=a.coda, onset=a.onset, total=a.total
            )
            for a in c.score.alignment
        ],
        source=c.source,
        band=band_of(c.score.score),
        similarity=similarity,
    )


def candidate_model(
    c: Candidate, slot: Slot | None, grid_data: Mapping[str, object] | None, similarity: float | None = None
) -> RhymeCandidateModel:
    return RhymeCandidateModel(**_candidate_fields(c, slot, grid_data, similarity))


def suggest_candidate_model(
    c: Candidate,
    slot: Slot | None,
    grid_data: Mapping[str, object] | None,
    similarity: float | None = None,
    rhythm: float | None = None,
) -> RhymeSuggestCandidateModel:
    return RhymeSuggestCandidateModel(**_candidate_fields(c, slot, grid_data, similarity), rhythm_fit=rhythm)


# --- anchor resolution ---


@dataclass(frozen=True, slots=True)
class ResolvedAnchor:
    unit: Unit
    record: SnippetRecord | None


async def resolve_anchor(anchor: Anchor, user_id: str, repo: SnippetRepository, dialect: DialectMap) -> ResolvedAnchor:
    match anchor:
        case TextAnchor(text=text):
            try:
                return ResolvedAnchor(unit_from_text(display_text(text).strip(), dialect), None)
            except PronunciationError as exc:
                raise RhymeQueryError(f"text anchor {text!r} has no pronounceable words") from exc
        case SnippetAnchor(snippet_id=snippet_id, line_index=line_index):
            record = await repo.snippet(snippet_id, user_id)
            if record is None:
                raise SnippetNotFoundError(f"snippet {snippet_id} not found")
            phonetics = snippet_phonetics(record)
            if not phonetics.lines:
                raise RhymeQueryError(f"snippet {snippet_id} has no text")
            if line_index is None:
                # the last line with words: a trailing line of only rests has nothing to rhyme on
                line = next((l for l in reversed(phonetics.lines) if l.words), phonetics.lines[-1])
            else:
                found = next((l for l in phonetics.lines if l.line_index == line_index), None)
                if found is None:
                    raise RhymeQueryError(f"snippet {snippet_id} has no line {line_index}")
                line = found
            start, end = resolve_word_range(anchor, len(line.words))
            unit = note_unit(record.id, line, start, dialect, record.tag_ids, word_end=end)
            if unit is None:
                where = f"words {start}-{end}" if end is not None else "line"
                raise RhymeQueryError(f"snippet {snippet_id} line {line.line_index} {where} has no pronounceable words")
            return ResolvedAnchor(unit, record)
    raise RhymeQueryError("unknown anchor kind")


def resolve_word_range(anchor: SnippetAnchor, word_count: int) -> tuple[int, int | None]:
    """``(word_offset, word_end)`` for the anchor: ``(0, None)`` for a whole line, else a checked
    ``[start, end)`` with ``0 <= start < end <= word_count`` (an omitted end runs to the line's end)."""
    if not anchor.has_range:
        return 0, None
    start = anchor.word_start or 0
    end = word_count if anchor.word_end is None else anchor.word_end
    if start >= word_count or end > word_count or start >= end:
        raise RhymeQueryError(
            f"snippet {anchor.snippet_id} word range {start}-{end} is outside the line's {word_count} words"
        )
    return start, end


async def context_tags(context: Sequence[Anchor], user_id: str, repo: SnippetRepository) -> frozenset[str]:
    tags: set[str] = set()
    for anchor in context:
        if isinstance(anchor, SnippetAnchor):
            record = await repo.snippet(anchor.snippet_id, user_id)
            if record is not None:
                tags.update(record.tag_ids)
    return frozenset(tags)


def note_units(
    records: Sequence[SnippetRecord], dialect: DialectMap, skip_lines: frozenset[tuple[str, int]]
) -> tuple[list[Unit], dict[str, SnippetRecord]]:
    units: list[Unit] = []
    by_id: dict[str, SnippetRecord] = {}
    for record in records:
        by_id[record.id] = record
        for line in snippet_phonetics(record).lines:
            if (record.id, line.line_index) in skip_lines:
                continue
            for word_offset in range(len(line.words)):
                unit = note_unit(record.id, line, word_offset, dialect, record.tag_ids)
                if unit is not None:
                    units.append(unit)
    return units, by_id


async def meaning_target(
    anchors: Sequence[Anchor], resolved: Sequence[ResolvedAnchor], store: SnippetEmbeddingStore
) -> list[TargetPart]:
    """The meaning target of a query: each distinct anchor snippet once (whole display text, or
    its current stored embedding) and each distinct text anchor's text."""
    snippets = [SnippetText(r.record.id, r.record.raw_text) for r in resolved if r.record is not None]
    parts = await snippet_parts(snippets, store)
    texts = [display_text(a.text).strip() for a in anchors if isinstance(a, TextAnchor)]
    parts.extend(TextPart(t) for t in dict.fromkeys(texts) if t)
    return parts


@dataclass(frozen=True, slots=True)
class RhythmOff:
    """``rhythm_fit`` was not asked for: today's path, untouched."""


RhythmPlan = RhythmOff | SlotRhythm | RhythmUnavailable


def rhythm_plan(requested: bool, first: ResolvedAnchor) -> RhythmPlan:
    """The slot rhythm of the request's first anchor, or why it has none.

    A text anchor has no score (``no_score``); a snippet anchor's slot is its line, or its word range,
    read from the snippet's stored score (``app.services.rhythm_fit.slot``).
    """
    if not requested:
        return RhythmOff()
    record, unit = first.record, first.unit
    if record is None or unit.line_index is None:
        return RhythmUnavailable("no_score")
    return stored_slot_rhythm(
        record.score, record.grid_data, unit.line_index, unit.word_offset or 0, unit.word_end, snippet_id=record.id
    )


def fits_rhythm_count(c: Candidate, slot: SlotRhythm) -> bool:
    """True when the candidate has exactly the slot's syllable count (rest marks are not syllables)."""
    return len(candidate_stresses(c.unit.stress)) == slot.syllable_count


def candidate_rhythm_fit(c: Candidate, slot: SlotRhythm) -> float:
    return rhythm_fit(slot, candidate_stresses(c.unit.stress))


def _rhythm_label(plan: RhythmPlan) -> str:
    match plan:
        case RhythmOff():
            return "off"
        case SlotRhythm():
            return "applied"
        case RhythmUnavailable(reason=reason):
            return reason
    raise AssertionError(plan)


DictionaryFactory = Callable[[DialectMap, ScoreWeights | None, bool], CandidateSource]


def default_dictionary(dialect: DialectMap, weights: ScoreWeights | None, include_rare: bool) -> CandidateSource:
    return DictionarySource(dialect, weights, include_rare)


# --- entry point ---


async def suggest(
    req: RhymeSuggestRequest,
    user_id: str,
    repo: SnippetRepository,
    *,
    dictionary: DictionaryFactory | None = None,
    meaning: MeaningService | None = None,
    embeddings: SnippetEmbeddingStore | None = None,
) -> RhymeSuggestResponse:
    """Ranked candidates for the anchors. ``order="meaning"`` needs ``meaning``; without it (or
    when the model is unavailable) the response is in today's order with ``order_applied="rhyme"``."""
    started = time.perf_counter()
    # an invalid filter is a request error before anything is looked up (TagExpressionError)
    tag_filter = parse(req.tag_filter) if req.tag_filter else None
    if not await repo.canvas_owned(req.canvas_id, user_id):
        raise CanvasNotFoundError(f"canvas {req.canvas_id} not found")
    dialect = resolve_dialect(req)
    weights = resolve_weights(req)

    resolved = [await resolve_anchor(a, user_id, repo, dialect) for a in req.anchors]
    anchors = [r.unit for r in resolved]
    rhythm = rhythm_plan(req.rhythm_fit, resolved[0])
    tags = await context_tags(req.context, user_id, repo)
    anchor_texts = {a.text.lower() for a in anchors}
    anchor_lines = frozenset((r.record.id, r.unit.line_index) for r in resolved if r.record is not None and r.unit.line_index is not None)
    exclude = frozenset(req.exclude_ids) | {a.id for a in anchors}

    records_by_id: dict[str, SnippetRecord] = {r.record.id: r.record for r in resolved if r.record is not None}
    sources: list[CandidateSource] = []
    if "notes" in req.sources:
        records = [r for r in await repo.user_snippets(user_id) if matches_filter(r, tag_filter)]
        units, by_id = note_units(records, dialect, anchor_lines)
        records_by_id.update(by_id)
        sources.append(NotesSource(units, dialect, weights))
    if "dictionary" in req.sources:
        sources.append((dictionary or default_dictionary)(dialect, weights, req.include_rare))

    def run_sources() -> list[Candidate]:
        found: list[Candidate] = []
        for source in sources:
            found.extend(source.suggest(anchors, req.k, req.min_score, SOURCE_LIMIT, exclude))
        return found

    candidates = await asyncio.to_thread(run_sources)
    candidates = [c for c in candidates if c.unit.text.lower() not in anchor_texts]
    if isinstance(rhythm, SlotRhythm):
        # before the per-line suffix dedupe, so a note line contributes its suffix of the right length
        slot_rhythm = rhythm
        candidates = [c for c in candidates if fits_rhythm_count(c, slot_rhythm)]
    candidates = dedupe_note_suffixes(candidates, req.slot)
    ranked = rank(candidates, tags)
    target = await meaning_target(req.anchors, resolved, embeddings or NoStoredEmbeddings()) if req.order == "meaning" else []
    ordered = await apply_order(
        req.order, ranked, score=lambda c: c.score.score, text=lambda c: c.unit.text, target=target, meaning=meaning
    )
    items: list[Ordered[Candidate]] = ordered.items
    fits: dict[int, float] = {}  # id(candidate) -> rhythm fit; empty unless rhythm applies
    if isinstance(rhythm, SlotRhythm):
        slot_rhythm = rhythm
        fits = {id(c): candidate_rhythm_fit(c, slot_rhythm) for c in ranked}
        items = order_by_rhythm(items, score=lambda c: c.score.score, fit=lambda c: fits[id(c)])
    page = items[req.offset : req.offset + req.limit]

    response = RhymeSuggestResponse(
        anchors=[unit_model(r.unit, r.record.grid_data if r.record else None) for r in resolved],
        candidates=[
            suggest_candidate_model(
                o.item,
                req.slot,
                records_by_id[o.item.unit.snippet_id].grid_data if o.item.unit.snippet_id in records_by_id else None,
                o.similarity,
                fits.get(id(o.item)),
            )
            for o in page
        ],
        total=len(ranked),
        elapsed_ms=round((time.perf_counter() - started) * 1000, 2),
        order_applied=ordered.applied,
        rhythm_applied=isinstance(rhythm, SlotRhythm),
        rhythm_unavailable_reason=rhythm.reason if isinstance(rhythm, RhythmUnavailable) else None,
    )
    logger.info(
        "rhyme_suggest.done anchors=%d sources=%s k=%d tag_filter=%s order=%s order_applied=%s rhythm=%s total=%d returned=%d elapsed_ms=%.1f",
        len(anchors), ",".join(req.sources), req.k, tag_filter is not None, req.order, ordered.applied,
        _rhythm_label(rhythm), response.total, len(response.candidates), response.elapsed_ms,
    )
    return response
