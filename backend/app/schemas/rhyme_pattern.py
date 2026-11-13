"""Request/response models for POST /api/nlp/rhyme-pattern/{detect,suggest}
(docs/features/rhyme_patterns.md; mirrored by the web and Android clients).

Range and span rules that need the snippet (lines in range, words in a line, order across slots)
are checked by the service and raised as typed ``pattern_*`` errors; the models only check what
the request alone can tell.
"""

from __future__ import annotations

from pydantic import BaseModel, Field, model_validator

from app.schemas.rhyme import (
    DialectMapModel,
    RhymeCandidateModel,
    RhymeGridLineModel,
    RhymeSyllable,
    RhymeUnitModel,
    ScoreWeightsModel,
    SourceName,
)

MAX_PATTERN_LINES = 16
MAX_PATTERN_SLOTS = 8
MAX_SLOT_MEMBERS = MAX_PATTERN_LINES
DETECT_MIN_SCORE = 0.75


class PatternLines(BaseModel):
    """A consecutive range ``[line_start, line_end)`` of one snippet's lines (2..16 lines with words)."""

    canvas_id: str = Field(min_length=1)
    snippet_id: str = Field(min_length=1)
    line_start: int = Field(ge=0)
    line_end: int = Field(ge=0)  # exclusive; checked against line_start by the service (pattern_range_invalid)
    weights: ScoreWeightsModel | None = None
    dialect: DialectMapModel | None = None


class SpanRef(BaseModel):
    """Words ``[word_start, word_end)`` of one line (word indexes count words only, rests excluded)."""

    line_index: int = Field(ge=0)
    word_start: int = Field(ge=0)
    word_end: int = Field(ge=1)  # exclusive; word_end > word_start is checked by the service (pattern_span_invalid)


class PatternSpanModel(SpanRef):
    text: str  # the span's words as written (display text)
    syllable_count: int
    stress: str
    rhyme_key: list[str]
    syllables: list[RhymeSyllable]


class PatternSlotModel(BaseModel):
    id: str
    members: list[PatternSpanModel]  # line order
    coverage: int
    full: bool
    default_on: bool
    k: int = Field(ge=1, le=4)
    cohesion: float
    syllable_count: int
    stress: str


class PatternWordModel(BaseModel):
    word_index: int
    text: str
    syllable_count: int
    stress: str


class PatternLineModel(BaseModel):
    line_index: int
    text: str
    words: list[PatternWordModel]
    grid_line: RhymeGridLineModel


class PatternDetectRequest(PatternLines):
    min_score: float = Field(DETECT_MIN_SCORE, ge=0.3, le=1.0)


class PatternDetectResponse(BaseModel):
    lines: list[PatternLineModel]
    slots: list[PatternSlotModel]
    elapsed_ms: float


class SlotSpec(BaseModel):
    id: str | None = Field(None, min_length=1)
    members: list[SpanRef] = Field(min_length=1, max_length=MAX_SLOT_MEMBERS)
    k: int | None = Field(None, ge=1, le=4)


class PatternSuggestRequest(PatternLines):
    slots: list[SlotSpec] = Field(min_length=1, max_length=MAX_PATTERN_SLOTS)
    min_score: float = Field(0.5, ge=0.0, le=1.0)
    sources: list[SourceName] = Field(default_factory=lambda: ["notes", "dictionary"], min_length=1)
    include_rare: bool = False
    tag_filter: str | None = None
    exclude_ids: list[str] = Field(default_factory=list)
    limit: int = Field(30, ge=1, le=100)
    offsets: dict[str, int] = Field(default_factory=dict)
    line_matches: bool = True
    line_match_limit: int = Field(30, ge=1, le=100)
    line_match_offset: int = Field(0, ge=0)

    @model_validator(mode="after")
    def _ids_unique_offsets_valid(self) -> "PatternSuggestRequest":
        ids = [s.id for s in self.slots if s.id is not None]
        if len(ids) != len(set(ids)):
            raise ValueError("slot ids must be unique")
        if any(offset < 0 for offset in self.offsets.values()):
            raise ValueError("offsets must be non-negative")
        return self


class PatternCandidateModel(RhymeCandidateModel):
    member_scores: list[float]  # score against each slot member, member order


class PatternSlotResult(BaseModel):
    slot: PatternSlotModel
    candidates: list[PatternCandidateModel]
    total: int


class LineMatchHit(BaseModel):
    slot_id: str
    word_start: int
    word_end: int
    text: str
    score: float


class LineMatchModel(BaseModel):
    unit: RhymeUnitModel
    hits: list[LineMatchHit]
    slots_hit: int
    score: float


class PatternSuggestResponse(BaseModel):
    slots: list[PatternSlotResult]
    line_matches: list[LineMatchModel]
    line_match_total: int
    elapsed_ms: float


class PatternErrorBody(BaseModel):
    error: str  # pattern_range_invalid | pattern_span_invalid | pattern_slot_order
    message: str


class PatternErrorResponse(BaseModel):
    detail: PatternErrorBody
