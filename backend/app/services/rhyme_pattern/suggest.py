"""Per-slot candidates and whole-line matches for POST /api/nlp/rhyme-pattern/suggest.

Pure given its inputs (records, indexes): ``app/services/rhyme_pattern_service.py`` loads the
data and runs ``compute`` in a worker thread.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from app.schemas.rhyme_pattern import LineMatchHit, LineMatchModel, PatternCandidateModel
from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.index import RhymeIndex
from app.services.phonetics.pattern.candidates import SlotCandidate, slot_candidates
from app.services.phonetics.pattern.line_match import HitOption, best_hits
from app.services.phonetics.pattern.spans import MAX_SPAN_WORDS, line_spans
from app.services.phonetics.pattern.types import PatternSlot
from app.services.phonetics.score import ScoreWeights
from app.services.phonetics.sources.base import SourceName
from app.services.phonetics_service import note_unit, split_lines
from app.services.rhyme_pattern.models import line_match_model, pattern_candidate_model
from app.services.rhyme_pattern.resolve import PatternLine, pattern_line
from app.services.rhyme_suggest_service import SnippetRecord, fits_slot, snippet_phonetics
from app.schemas.rhyme import Slot


@dataclass(frozen=True, slots=True)
class NoteSpans:
    """A RhymeIndex over the spans of the eligible note lines, and those lines."""

    index: RhymeIndex
    records: Mapping[str, SnippetRecord]
    lines: Mapping[tuple[str, int], PatternLine]


def build_note_spans(
    records: Sequence[SnippetRecord],
    dialect: DialectMap,
    skip_lines: frozenset[tuple[str, int]],
    max_span_words: int = MAX_SPAN_WORDS,
) -> NoteSpans:
    """Every detection span (``spans.line_spans``) of every line of ``records``, except ``skip_lines``."""
    index = RhymeIndex(dialect)
    lines: dict[tuple[str, int], PatternLine] = {}
    for record in records:
        raw_lines = split_lines(record.raw_text)
        for line in snippet_phonetics(record).lines:
            if (record.id, line.line_index) in skip_lines or not line.words:
                continue
            pline = pattern_line(record, line, raw_lines)
            lines[(record.id, line.line_index)] = pline
            for span in line_spans(record.id, pline.words, dialect, max_span_words, record.tag_ids):
                index.insert(span.unit)
    return NoteSpans(index=index, records={r.id: r for r in records}, lines=lines)


@dataclass(frozen=True, slots=True)
class SlotRequest:
    id: str
    slot: PatternSlot


@dataclass(frozen=True, slots=True)
class Ranked:
    found: SlotCandidate
    source: SourceName


def _words(text: str) -> tuple[str, ...]:
    return tuple(text.lower().split())


def repeats_member(text: str, member_words: Sequence[tuple[str, ...]]) -> bool:
    """The text is a member's text, or ends with it ("so steady" for the member "steady")."""
    words = _words(text)
    return any(words[len(words) - len(m) :] == m for m in member_words if 0 < len(m) <= len(words))


def _source_rank(source: SourceName) -> int:
    match source:
        case "notes":
            return 0
        case "dictionary":
            return 1


def _dedupe_note_lines(found: Sequence[SlotCandidate], slot: Slot) -> list[SlotCandidate]:
    """One span per (snippet, line): best score, then fits the slot, then the longest, then the earliest."""
    best: dict[tuple[str | None, int | None], SlotCandidate] = {}

    def key(c: SlotCandidate) -> tuple[float, int, int, int, str]:
        words = (c.unit.word_end or 0) - (c.unit.word_offset or 0)
        return (-c.score, 0 if fits_slot(c.unit.stress, slot) else 1, -words, c.unit.word_offset or 0, c.unit.id)

    for c in found:
        line = (c.unit.snippet_id, c.unit.line_index)
        current = best.get(line)
        if current is None or key(c) < key(current):
            best[line] = c
    return list(best.values())


def rank_slot(
    slot: PatternSlot,
    dictionary: Sequence[SlotCandidate],
    notes: Sequence[SlotCandidate],
) -> list[Ranked]:
    """Drop units whose text is or ends with a member's text, keep one span per note line, then
    rank: score, mean member score, match depth, notes before dictionary, id."""
    member_words = [_words(m.unit.text) for m in slot.members]
    fit = Slot(syllable_count=slot.syllable_count, stress=slot.stress)
    kept_notes = _dedupe_note_lines([c for c in notes if not repeats_member(c.unit.text, member_words)], fit)
    ranked = [Ranked(c, "notes") for c in kept_notes]
    ranked += [Ranked(c, "dictionary") for c in dictionary if not repeats_member(c.unit.text, member_words)]
    ranked.sort(key=lambda r: (-r.found.score, -r.found.mean, -r.found.match_depth, _source_rank(r.source), r.found.unit.id))
    return ranked


@dataclass(frozen=True, slots=True)
class SlotPage:
    candidates: list[PatternCandidateModel]
    total: int


@dataclass(frozen=True, slots=True)
class SuggestResult:
    pages: list[SlotPage]
    line_matches: list[LineMatchModel]
    line_match_total: int


@dataclass(frozen=True, slots=True)
class SuggestParams:
    snippet_id: str
    sources: frozenset[SourceName]
    min_score: float
    exclude: frozenset[str]
    weights: ScoreWeights | None
    limit: int
    offsets: Mapping[str, int]
    line_matches: bool
    line_match_limit: int
    line_match_offset: int


def compute(
    slots: Sequence[SlotRequest],
    params: SuggestParams,
    dialect: DialectMap,
    notes: NoteSpans | None,
    dictionary: RhymeIndex | None,
) -> SuggestResult:
    pages: list[SlotPage] = []
    note_hits: list[list[SlotCandidate]] = []
    for request in slots:
        slot = request.slot
        members = [m.unit for m in slot.members]
        note_found = (
            slot_candidates(notes.index, members, slot.k, params.min_score, params.exclude, params.weights)
            if notes is not None
            else []
        )
        note_hits.append(note_found)
        dict_found = (
            slot_candidates(dictionary, members, slot.k, params.min_score, params.exclude, params.weights)
            if dictionary is not None and "dictionary" in params.sources
            else []
        )
        ranked = rank_slot(slot, dict_found, note_found if "notes" in params.sources else [])
        offset = params.offsets.get(request.id, 0)
        page = ranked[offset : offset + params.limit]
        pages.append(
            SlotPage(
                candidates=[
                    pattern_candidate_model(
                        r.found,
                        r.source,
                        slot,
                        params.snippet_id,
                        _grid_of(r.found, notes) if r.source == "notes" else None,
                    )
                    for r in page
                ],
                total=len(ranked),
            )
        )
    matches = line_matches(slots, note_hits, notes, dialect, params.exclude) if params.line_matches and notes else []
    window = matches[params.line_match_offset : params.line_match_offset + params.line_match_limit]
    return SuggestResult(pages=pages, line_matches=window, line_match_total=len(matches))


def _grid_of(found: SlotCandidate, notes: NoteSpans | None) -> Mapping[str, object] | None:
    if notes is None or found.unit.snippet_id is None:
        return None
    record = notes.records.get(found.unit.snippet_id)
    return record.grid_data if record is not None else None


def line_matches(
    slots: Sequence[SlotRequest],
    note_hits: Sequence[Sequence[SlotCandidate]],
    notes: NoteSpans,
    dialect: DialectMap,
    exclude: frozenset[str],
) -> list[LineMatchModel]:
    """Note lines with at least 2 slots hit in slot order and word order (``line_match.best_hits``),
    ranked by slots hit, mean hit score, line unit id."""
    options: dict[tuple[str, int], list[HitOption]] = {}
    for slot_index, found in enumerate(note_hits):
        for c in found:
            unit = c.unit
            if unit.snippet_id is None or unit.line_index is None or unit.word_offset is None or unit.word_end is None:
                continue
            options.setdefault((unit.snippet_id, unit.line_index), []).append(
                HitOption(slot=slot_index, word_start=unit.word_offset, word_end=unit.word_end, score=c.score, unit_id=unit.id)
            )
    results: list[LineMatchModel] = []
    for (snippet_id, line_index), opts in options.items():
        if len({o.slot for o in opts}) < 2:
            continue
        line = notes.lines.get((snippet_id, line_index))
        record = notes.records.get(snippet_id)
        if line is None or record is None:
            continue
        unit = note_unit(snippet_id, line.phonetics, 0, dialect, record.tag_ids)
        if unit is None or unit.id in exclude:
            continue
        hits = best_hits(opts, len(line.words.words), len(slots))
        if len(hits) < 2:
            continue
        results.append(
            line_match_model(
                unit,
                line.text,
                record.grid_data,
                [
                    LineMatchHit(
                        slot_id=slots[h.slot].id,
                        word_start=h.word_start,
                        word_end=h.word_end,
                        text=line.display(h.word_start, h.word_end),
                        score=h.score,
                    )
                    for h in hits
                ],
            )
        )
    results.sort(key=lambda m: (-m.slots_hit, -m.score, m.unit.id))
    return results
