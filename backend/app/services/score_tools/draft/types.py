"""Typed values of the score drafts (docs/features/score_draft_export.md#recording-draft)."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from app.services.score import Score


@dataclass(frozen=True, slots=True)
class DraftResult:
    """What ``POST /api/snippets/{id}/score/draft`` answers; the score is not saved."""

    score: Score
    bpm_estimated: float | None  # the tempo the server estimated (bpm was null), else None
    unplaced: int  # grid syllables with no note
    unmatched_words: int  # take words that matched no grid word (0 for a grid draft)


@dataclass(frozen=True, slots=True)
class GridWord:
    """One word group of the grid (score.grid_view.word_groups) with what the draft needs."""

    line: int
    ordinals: tuple[int, ...]
    syllables: tuple[str, ...]  # syllable text per ordinal ("" when missing)
    accents: tuple[int, ...]  # default accent per ordinal (stress 1 -> 1)
    key: str  # score.normalize.word_key of the spelling


@dataclass(frozen=True, slots=True)
class TakeWord:
    """One STT word of the take, times made monotonic (start never before the previous start)."""

    key: str
    start_ms: float
    end_ms: float  # >= start_ms


class PairKind(StrEnum):
    exact = "exact"  # equal word keys, an lcs_pairs match
    fill = "fill"  # positional pairing inside a gap with as many take words as grid words


@dataclass(frozen=True, slots=True)
class WordPair:
    take: int  # index into the take words
    grid: int  # index into the grid words (all lines, in order)
    kind: PairKind


@dataclass(frozen=True, slots=True)
class Alignment:
    pairs: tuple[WordPair, ...]  # strictly increasing in both coordinates
    unmatched_take_words: int


@dataclass(frozen=True, slots=True)
class SyllableTime:
    """A placed syllable's sung time before quantization."""

    line: int
    s: int
    start_ms: float
    end_ms: float
    word_start: bool  # first syllable of its take word: a real onset (feeds the tempo follower)
    accent: int


class Grid(StrEnum):
    """The lattice a beat is quantized on."""

    straight = "straight"  # 1/16 = 12 ticks
    triplet = "triplet"  # 1/16t = 8 ticks

    @property
    def step(self) -> int:
        return 12 if self is Grid.straight else 8


@dataclass(frozen=True, slots=True)
class QuantizedNote:
    t: int
    d: int


@dataclass(frozen=True, slots=True)
class Quantized:
    notes: tuple[QuantizedNote, ...]  # one per SyllableTime, same order; strictly ordered, no overlap
    pickup: int
    triplet_beats: int  # beats quantized on the triplet lattice
    straight_beats: int  # beats with an off-beat onset quantized on the straight lattice
