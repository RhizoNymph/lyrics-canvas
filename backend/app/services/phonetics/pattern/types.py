"""Frozen types of the rhyme-pattern engine (docs/features/rhyme_patterns.md)."""

from __future__ import annotations

from dataclasses import dataclass

from app.services.phonetics.score import MAX_K
from app.services.phonetics.types import Unit, WordPronunciation


@dataclass(frozen=True, slots=True, order=True)
class SpanPos:
    """Words ``[word_start, word_end)`` of line ``line_index`` (word indexes count words only)."""

    line_index: int
    word_start: int
    word_end: int

    def __post_init__(self) -> None:
        if self.word_start < 0 or self.word_end <= self.word_start:
            raise ValueError(f"invalid span {self.word_start}-{self.word_end}")

    @property
    def word_count(self) -> int:
        return self.word_end - self.word_start

    def overlaps(self, other: SpanPos) -> bool:
        return self.line_index == other.line_index and self.word_start < other.word_end and other.word_start < self.word_end


@dataclass(frozen=True, slots=True)
class LineWords:
    """One pattern line as the engine reads it: stored (override-applied) word pronunciations,
    dialect not yet applied. ``words[i]`` is word ``i`` of the line."""

    line_index: int
    words: tuple[WordPronunciation, ...]


@dataclass(frozen=True, slots=True)
class PatternSpan:
    """A span and its unit. ``tail`` = syllables from the span's first stressed (1 or 2) syllable
    to its end (the last syllable when none is stressed); the rhyme is scored over ``k`` of them."""

    pos: SpanPos
    unit: Unit
    tail: int

    @property
    def k(self) -> int:
        return max(1, min(MAX_K, self.tail))

    @property
    def tail_stress(self) -> str:
        return self.unit.stress[-self.tail :]


@dataclass(frozen=True, slots=True)
class PatternSlot:
    """An ordered rhyme position: at most one member per line, members in line order.

    ``k`` is the depth candidates are scored at (1..4); ``cohesion`` the weakest pairwise member
    score (1.0 for one member); ``stress``/``syllable_count`` the rhyme-tail shape candidates should
    fit; ``line_count`` the number of pattern lines (lines with words)."""

    members: tuple[PatternSpan, ...]
    k: int
    cohesion: float
    stress: str
    line_count: int

    @property
    def coverage(self) -> int:
        return len(self.members)

    @property
    def syllable_count(self) -> int:
        return len(self.stress)

    @property
    def full(self) -> bool:
        return self.coverage == self.line_count

    @property
    def default_on(self) -> bool:
        return default_on(self.coverage, self.line_count)


def default_on(coverage: int, line_count: int) -> bool:
    """Full slots, and with 4+ lines also those covering at least ``ceil(0.75 * lines)`` lines."""
    if coverage >= line_count:
        return True
    if line_count < 4:
        return False
    return coverage >= max(2, -(-3 * line_count // 4))
