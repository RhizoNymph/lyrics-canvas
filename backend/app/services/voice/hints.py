"""Vocabulary hints: the Whisper ``initial_prompt`` built from a user's word bank. Pure; no I/O.

Contract section 2 (docs/features/voice_dataset.md#vocabulary-hints):

1. Every bank word with at least one letter is offered (phrases too).
2. Ranked by importance: rarer first (wordfreq Zipf frequency ascending; unknown = 0), then newer
   (``created_at`` desc), then ``lower(text)`` ascending.
3. The most important words are kept while ``"Vocabulary: " + ", ".join(kept) + "."`` fits within
   ``max_chars`` code points; the first word that does not fit ends the list.
4. Kept words are emitted in reverse importance order, most important last: Whisper keeps the end
   of an over-long prompt.
5. No word fits, or nothing is offered: no prompt (``None``).
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import datetime

from wordfreq import zipf_frequency

PREFIX = "Vocabulary: "
SEPARATOR = ", "
SUFFIX = "."

Frequency = Callable[[str], float]


@dataclass(frozen=True, slots=True)
class HintWord:
    text: str
    created_at: datetime


@dataclass(frozen=True, slots=True)
class HintPrompt:
    text: str  # exactly what is sent as initial_prompt
    words_offered: int  # bank words with a letter
    words_used: int  # >= 1

    @property
    def chars(self) -> int:
        return len(self.text)


def zipf_en(text: str) -> float:
    """English Zipf frequency of a word or phrase; 0 for anything wordfreq does not know."""
    return zipf_frequency(text, "en")


def eligible_words(words: Sequence[HintWord]) -> list[HintWord]:
    return [w for w in words if any(c.isalpha() for c in w.text)]


def rank_hint_words(words: Sequence[HintWord], frequency: Frequency = zipf_en) -> list[HintWord]:
    """Eligible words, most important first."""
    return sorted(
        eligible_words(words),
        key=lambda w: (frequency(w.text), -w.created_at.timestamp(), w.text.lower()),
    )


def _length(kept_lengths: int, count: int) -> int:
    return len(PREFIX) + kept_lengths + len(SEPARATOR) * max(0, count - 1) + len(SUFFIX)


def build_hint_prompt(words: Sequence[HintWord], max_chars: int, frequency: Frequency = zipf_en) -> HintPrompt | None:
    ranked = rank_hint_words(words, frequency)
    kept: list[str] = []
    total = 0
    for candidate in ranked:
        if _length(total + len(candidate.text), len(kept) + 1) > max_chars:
            break
        kept.append(candidate.text)
        total += len(candidate.text)
    if not kept:
        return None
    text = PREFIX + SEPARATOR.join(reversed(kept)) + SUFFIX
    return HintPrompt(text=text, words_offered=len(ranked), words_used=len(kept))
