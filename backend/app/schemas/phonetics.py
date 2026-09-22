"""Pydantic models for the ``snippets.phonetics`` JSONB column."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

Stress = Literal[0, 1, 2]
SourceName = Literal["cmudict", "g2p", "heuristic", "override"]


class SyllableModel(BaseModel):
    onset: list[str] = Field(default_factory=list)
    nucleus: str
    coda: list[str] = Field(default_factory=list)
    stress: Stress


class WordPhonetics(BaseModel):
    word: str
    source: SourceName
    syllables: list[SyllableModel] = Field(default_factory=list)


class LinePhonetics(BaseModel):
    line_index: int
    words: list[WordPhonetics] = Field(default_factory=list)
    stress: str
    syllable_count: int
    rhyme_key: list[str] = Field(default_factory=list)
    rhyme_keys: dict[str, list[str]] = Field(default_factory=dict)


class PronunciationOverride(BaseModel):
    syllables: list[SyllableModel]


class SnippetPhonetics(BaseModel):
    dialect_digest: str
    lines: list[LinePhonetics] = Field(default_factory=list)
    overrides: dict[str, PronunciationOverride] = Field(default_factory=dict)


class DerivedFromModel(BaseModel):
    provenance: Literal["note", "dictionary", "datamuse", "generated"]
    source_unit_id: str
    score: float
    match_depth: int
    k: int
    anchor_unit_id: str
    anchor_text: str


def override_key(line_index: int, word_index: int) -> str:
    return f"{line_index}:{word_index}"
