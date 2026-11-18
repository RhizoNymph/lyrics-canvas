"""Typed slot rhythm for rhyme-suggest "Fit rhythm" (docs/features/rhyme_rhythm_fit.md).

A ``SlotRhythm`` is the rhythm of the anchor's slot syllables, read from the anchor snippet's score:
one ``SlotBeat`` per slot syllable, in order. It is never empty (``slot_rhythm`` answers
``RhythmUnavailable("slot_unplaced")`` instead), so holding one means the filter and the score apply.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Literal

RhythmUnavailableReason = Literal["no_score", "slot_unplaced"]

# An accent raises a syllable's level to at least: accent 1 like a beat (2), accent 2 like a downbeat (3).
ACCENT_LEVEL: Mapping[int, int] = {0: 0, 1: 2, 2: 3}
MAX_LEVEL = 3


@dataclass(frozen=True, slots=True)
class SlotBeat:
    """One slot syllable: its note's onset tick, ``metric_weight`` there (0..3) and accent (0..2)."""

    onset: int
    weight: int
    accent: int

    def __post_init__(self) -> None:
        if not 0 <= self.weight <= MAX_LEVEL:
            raise ValueError(f"metric weight must be 0..3, got {self.weight}")
        if self.accent not in ACCENT_LEVEL:
            raise ValueError(f"accent must be 0..2, got {self.accent}")

    @property
    def level(self) -> int:
        """Prominence 0..3: the metric weight, raised by the accent (never lowered)."""
        return max(self.weight, ACCENT_LEVEL[self.accent])


@dataclass(frozen=True, slots=True)
class SlotRhythm:
    beats: tuple[SlotBeat, ...]

    def __post_init__(self) -> None:
        if not self.beats:
            raise ValueError("a slot rhythm has at least one syllable")

    @property
    def syllable_count(self) -> int:
        return len(self.beats)

    @property
    def levels(self) -> tuple[int, ...]:
        return tuple(b.level for b in self.beats)


@dataclass(frozen=True, slots=True)
class RhythmUnavailable:
    """Fit rhythm was asked for but cannot apply; the response says why."""

    reason: RhythmUnavailableReason


SlotRhythmResult = SlotRhythm | RhythmUnavailable
