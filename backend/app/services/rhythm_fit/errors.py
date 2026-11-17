"""Typed failures of the rhythm-fit helpers (programming errors: callers filter before scoring)."""

from __future__ import annotations


class RhythmFitError(Exception):
    """Base of every rhythm-fit failure."""


class StressPatternError(RhythmFitError):
    """A candidate stress string holds something other than stress digits and rest marks."""

    def __init__(self, stress: str, position: int) -> None:
        super().__init__(f"stress pattern {stress!r} has an invalid character at {position}")
        self.stress = stress
        self.position = position


class SyllableCountMismatch(RhythmFitError):
    """A candidate was scored against a slot with a different syllable count."""

    def __init__(self, expected: int, got: int) -> None:
        super().__init__(f"slot has {expected} syllables, candidate has {got}")
        self.expected = expected
        self.got = got
