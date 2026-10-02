"""Request/response models for POST /api/nlp/rhyme-suggest (mirrored in frontend/src/types/rhyme.ts)."""

from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

SourceName = Literal["notes", "dictionary"]
ProvenanceName = Literal["note", "dictionary", "datamuse", "generated"]
STRESS_PATTERN = r"^[012]+$"


class SnippetAnchor(BaseModel):
    """A snippet line, or a word range of it (``word_end`` exclusive; None = to the end of the line)."""

    kind: Literal["snippet"]
    snippet_id: str = Field(min_length=1)
    line_index: int | None = None  # None = last non-empty line
    word_start: int | None = Field(None, ge=0)
    word_end: int | None = Field(None, ge=1)

    @model_validator(mode="after")
    def _range_is_ordered(self) -> "SnippetAnchor":
        if self.word_start is not None and self.word_end is not None and self.word_start >= self.word_end:
            raise ValueError("word_start must be less than word_end")
        return self

    @property
    def has_range(self) -> bool:
        return self.word_start is not None or self.word_end is not None


class TextAnchor(BaseModel):
    kind: Literal["text"]
    text: str = Field(min_length=1)

    @field_validator("text")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("text anchor must not be blank")
        return value


Anchor = Annotated[SnippetAnchor | TextAnchor, Field(discriminator="kind")]


class Slot(BaseModel):
    syllable_count: int = Field(ge=1, le=64)
    stress: str = Field(pattern=STRESS_PATTERN)

    @model_validator(mode="after")
    def _lengths_agree(self) -> "Slot":
        if len(self.stress) != self.syllable_count:
            raise ValueError("slot stress length must equal syllable_count")
        return self


class ScoreWeightsModel(BaseModel):
    vowel: float = Field(1.0, ge=0)
    coda: float = Field(0.6, ge=0)
    onset: float = Field(0.25, ge=0)


class DialectRuleModel(BaseModel):
    kind: Literal["merge", "final_merge", "optional_final"]
    source: list[str] = Field(min_length=1)
    target: list[str] = Field(default_factory=list)


class DialectMapModel(BaseModel):
    rules: list[DialectRuleModel] = Field(default_factory=list)


class RhymeSuggestRequest(BaseModel):
    canvas_id: str = Field(min_length=1)
    anchors: list[Anchor] = Field(min_length=1)
    context: list[Anchor] = Field(default_factory=list)
    slot: Slot | None = None
    k: int = Field(2, ge=1, le=4)
    min_score: float = Field(0.5, ge=0.0, le=1.0)
    sources: list[SourceName] = Field(default_factory=lambda: ["notes", "dictionary"], min_length=1)
    include_rare: bool = False
    exclude_ids: list[str] = Field(default_factory=list)
    limit: int = Field(40, ge=1, le=100)
    offset: int = Field(0, ge=0)
    weights: ScoreWeightsModel | None = None
    dialect: DialectMapModel | None = None


class RhymeSyllable(BaseModel):
    onset: list[str]
    nucleus: str
    coda: list[str]
    stress: Literal[0, 1, 2]


class RhymeGridCellModel(BaseModel):
    """A grid cell in a rhyme unit's ``grid_line``. Unlike the snippet ``GridCellModel`` it emits
    every field (unset ones as null), which is the rhyme-suggest wire shape the web client reads."""

    model_config = ConfigDict(extra="allow")

    syllable: str | None = None
    stress: int | None = None
    word_index: int | None = None
    rest: bool | None = None


class RhymeGridLineModel(BaseModel):
    cells: list[RhymeGridCellModel]
    syllable_count: int
    raw_text: str


class RhymeUnitModel(BaseModel):
    id: str
    text: str
    provenance: ProvenanceName
    ephemeral: bool
    syllable_count: int
    stress: str
    rhyme_key: list[str]
    syllables: list[RhymeSyllable]
    grid_line: RhymeGridLineModel
    snippet_id: str | None = None
    line_index: int | None = None
    word_offset: int | None = None
    word_end: int | None = None  # set only for word-range anchors (exclusive)
    tags: list[str] = Field(default_factory=list)


class SyllableAlignmentModel(BaseModel):
    cand_index: int | None
    anchor_index: int | None
    nucleus: float
    coda: float
    onset: float
    total: float


class RhymeCandidateModel(RhymeUnitModel):
    score: float
    match_depth: int
    best_anchor_id: str
    fits_slot: bool
    alignment: list[SyllableAlignmentModel]
    source: SourceName


class RhymeSuggestResponse(BaseModel):
    anchors: list[RhymeUnitModel]
    candidates: list[RhymeCandidateModel]
    total: int
    elapsed_ms: float
