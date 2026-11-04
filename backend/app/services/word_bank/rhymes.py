"""``POST /api/word-bank/rhymes``: rank bank words against one anchor. Pure.

Each candidate gets the directed score rhyme-suggest would give it for the anchor
(app/services/word_bank/phonetic.py). Below ``min_score`` is dropped; the rest sort by score
desc, match_depth desc, lower(text) asc (then id), and ``limit`` is applied last.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from app.services.phonetics.types import Unit
from app.services.word_bank.phonetic import PairScorer, PhoneticWord


@dataclass(frozen=True, slots=True)
class RhymeMatch:
    word: PhoneticWord
    score: float
    match_depth: int


def rank_rhymes(
    anchor: Unit,
    candidates: Sequence[PhoneticWord],
    scorer: PairScorer,
    *,
    min_score: float,
    limit: int,
    exclude_id: str | None = None,
    exclude_text: str | None = None,
) -> list[RhymeMatch]:
    """Matches for ``anchor`` among ``candidates``, skipping the word ``exclude_id``, any word equal
    to ``exclude_text`` ignoring case, and words with no pronunciation."""
    if limit < 1:
        raise ValueError(f"limit must be positive, got {limit}")
    excluded_text = exclude_text.lower() if exclude_text is not None else None
    matches: list[RhymeMatch] = []
    for word in candidates:
        if word.unit is None or word.id == exclude_id or word.sort_key == excluded_text:
            continue
        result = scorer.directed(anchor, word.unit)
        if result.score < min_score:
            continue
        matches.append(RhymeMatch(word=word, score=result.score, match_depth=result.match_depth))
    matches.sort(key=lambda m: (-m.score, -m.match_depth, m.word.sort_key, m.word.id))
    return matches[:limit]
