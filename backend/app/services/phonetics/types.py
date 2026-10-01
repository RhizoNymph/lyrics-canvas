"""Frozen engine types. Phones are ARPAbet strings with stress digits stripped."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Literal, Mapping

Stress = Literal[0, 1, 2]


class PronunciationSource(StrEnum):
    cmudict = "cmudict"
    g2p = "g2p"
    heuristic = "heuristic"
    override = "override"


@dataclass(frozen=True, slots=True)
class Syllable:
    onset: tuple[str, ...]
    nucleus: str
    coda: tuple[str, ...]
    stress: Stress
    deletable_coda: int = 0

    @property
    def phones(self) -> tuple[str, ...]:
        return (*self.onset, self.nucleus, *self.coda)


@dataclass(frozen=True, slots=True)
class WordPronunciation:
    word: str
    syllables: tuple[Syllable, ...]
    source: PronunciationSource


@dataclass(frozen=True, slots=True)
class Pronunciation:
    words: tuple[WordPronunciation, ...]

    @property
    def syllables(self) -> tuple[Syllable, ...]:
        return tuple(s for w in self.words for s in w.syllables)


RhymeKey = tuple[str, ...]


class Provenance(StrEnum):
    note = "note"
    dictionary = "dictionary"
    datamuse = "datamuse"
    generated = "generated"


@dataclass(frozen=True, slots=True)
class DerivedFrom:
    provenance: Provenance
    source_unit_id: str
    score: float
    match_depth: int
    k: int
    anchor_unit_id: str
    anchor_text: str


@dataclass(frozen=True, slots=True)
class Unit:
    """A rhymeable thing: a dictionary word, a note line suffix, or a typed phrase.

    Every field after ``pronunciation_override`` is derived by ``units.build_unit`` from
    ``pronunciation_override or pronunciation`` under the dialect map. Nothing else sets them.
    """

    id: str
    text: str
    pronunciation: Pronunciation
    provenance: Provenance
    ephemeral: bool
    pronunciation_override: Pronunciation | None
    tags: tuple[str, ...]
    derived_from: DerivedFrom | None
    snippet_id: str | None
    line_index: int | None
    word_offset: int | None
    # derived
    effective_pronunciation: Pronunciation
    dialect_digest: str
    stress: str
    syllable_count: int
    rhyme_key: RhymeKey
    rhyme_keys: Mapping[int, RhymeKey]
