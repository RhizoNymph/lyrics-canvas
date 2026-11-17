"""Split a sung word's time over its grid syllables (docs/features/score_draft_export.md#2-syllable-times).

Each syllable weighs its number of vowel letters (``a e i o u y`` after removing accents, at least
1); the word's ``[start, end]`` is cut into contiguous parts proportional to the weights, so the
syllable holding the long vowel gets the long part.
"""

from __future__ import annotations

import unicodedata
from collections.abc import Sequence

VOWELS = frozenset("aeiouy")


def vowel_weight(syllable: str) -> int:
    """Vowel letters of ``syllable`` (accents stripped, case folded), at least 1."""
    decomposed = unicodedata.normalize("NFD", syllable.lower())
    return max(1, sum(1 for c in decomposed if c in VOWELS))


def split_word(start_ms: float, end_ms: float, syllables: Sequence[str]) -> list[tuple[float, float]]:
    """``[(start, end)]`` per syllable: contiguous, first start = ``start_ms``, last end = ``end_ms``."""
    weights = [vowel_weight(s) for s in syllables]
    total = sum(weights)
    span = end_ms - start_ms
    out: list[tuple[float, float]] = []
    before = 0
    for weight in weights:
        part_start = start_ms + span * before / total
        before += weight
        part_end = end_ms if before == total else start_ms + span * before / total
        out.append((part_start, part_end))
    return out
