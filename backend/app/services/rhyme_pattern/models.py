"""Engine results -> the rhyme-pattern response models."""

from __future__ import annotations

from collections.abc import Mapping

from app.schemas.rhyme import RhymeGridLineModel, RhymeSyllable, Slot
from app.schemas.rhyme_pattern import (
    LineMatchHit,
    LineMatchModel,
    PatternCandidateModel,
    PatternLineModel,
    PatternSlotModel,
    PatternSpanModel,
    PatternWordModel,
)
from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics.pattern.candidates import SlotCandidate
from app.services.phonetics.pattern.types import PatternSlot, PatternSpan
from app.services.phonetics.sources.base import Candidate, SourceName
from app.services.phonetics.types import Unit
from app.services.rhyme_pattern.resolve import PatternLine, PatternRange
from app.services.rhyme_suggest_service import candidate_model, unit_model
from app.services.phonetics.units import stress_string


def pattern_span_id(snippet_id: str, span: PatternSpan) -> str:
    """``pattern:<snippet_id>:<line>:<start>-<end>``: a slot member as a candidate's best anchor."""
    return f"pattern:{snippet_id}:{span.pos.line_index}:{span.pos.word_start}-{span.pos.word_end}"


def full_grid_line(line: PatternLine) -> RhymeGridLineModel:
    if line.grid is not None:
        return RhymeGridLineModel.model_validate(line.grid)
    lines = syllabify_text(line.text)["lines"]
    if lines:
        return RhymeGridLineModel.model_validate(lines[0])
    return RhymeGridLineModel(cells=[], syllable_count=0, raw_text=line.text)


def line_model(line: PatternLine) -> PatternLineModel:
    return PatternLineModel(
        line_index=line.line_index,
        text=line.text,
        words=[
            PatternWordModel(
                word_index=i,
                text=line.tokens[i],
                syllable_count=len(word.syllables),
                stress=stress_string(word.syllables),
            )
            for i, word in enumerate(line.words.words)
        ],
        grid_line=full_grid_line(line),
    )


def span_model(span: PatternSpan, rng: PatternRange) -> PatternSpanModel:
    line = rng.line(span.pos.line_index)
    text = line.display(span.pos.word_start, span.pos.word_end) if line is not None else span.unit.text
    unit = span.unit
    return PatternSpanModel(
        line_index=span.pos.line_index,
        word_start=span.pos.word_start,
        word_end=span.pos.word_end,
        text=text,
        syllable_count=unit.syllable_count,
        stress=unit.stress,
        rhyme_key=list(unit.rhyme_key),
        syllables=[
            RhymeSyllable(onset=list(s.onset), nucleus=s.nucleus, coda=list(s.coda), stress=s.stress)
            for s in unit.effective_pronunciation.syllables
        ],
    )


def slot_model(slot_id: str, slot: PatternSlot, rng: PatternRange) -> PatternSlotModel:
    return PatternSlotModel(
        id=slot_id,
        members=[span_model(m, rng) for m in slot.members],
        coverage=slot.coverage,
        full=slot.full,
        default_on=slot.default_on,
        k=slot.k,
        cohesion=slot.cohesion,
        syllable_count=slot.syllable_count,
        stress=slot.stress,
    )


def pattern_candidate_model(
    found: SlotCandidate,
    source: SourceName,
    slot: PatternSlot,
    snippet_id: str,
    grid_data: Mapping[str, object] | None,
) -> PatternCandidateModel:
    weakest = found.weakest
    base = candidate_model(
        Candidate(
            unit=found.unit,
            score=found.scores[weakest],
            best_anchor_id=pattern_span_id(snippet_id, slot.members[weakest]),
            source=source,
        ),
        Slot(syllable_count=slot.syllable_count, stress=slot.stress),
        grid_data,
    )
    return PatternCandidateModel(**base.model_dump(), member_scores=[s.score for s in found.scores])


def line_match_model(
    unit: Unit,
    display: str,
    grid_data: Mapping[str, object] | None,
    hits: list[LineMatchHit],
) -> LineMatchModel:
    model = unit_model(unit, grid_data).model_copy(update={"text": display})
    return LineMatchModel(unit=model, hits=hits, slots_hit=len(hits), score=sum(h.score for h in hits) / len(hits))
