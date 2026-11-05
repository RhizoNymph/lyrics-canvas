"""Bank words as phonetic units, and the memoized pair scorer shared by rhymes and families.

A directed score is what rhyme-suggest gives candidate ``b`` for anchor ``a``: the default dialect
map, default weights, and ``score(a, b, min(k, syllables(a)))`` over the effective pronunciations
(RhymeIndex.query scores at ``k_eff = min(k, len(anchor))``). Pure; no DB.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass

from app.services.phonetics import DEFAULT_DIALECT_MAP, DialectMap, PronunciationError, unit_from_text
from app.services.phonetics.score import (
    DEFAULT_WEIGHTS,
    DEPTH_WEIGHTS,
    MATCH_DEPTH_THRESHOLD,
    MAX_K,
    ScoreWeights,
    align_syllable,
)
from app.services.phonetics.types import Syllable, Unit


@dataclass(frozen=True, slots=True)
class PairScore:
    """``RhymeScore.score`` and ``match_depth`` of a directed pair (no alignment)."""

    score: float
    match_depth: int


@dataclass(frozen=True, slots=True)
class PhoneticWord:
    """A bank word with its unit, built once per request (None when it has no pronounceable syllable)."""

    id: str
    text: str
    sort_key: str  # lower(text)
    unit: Unit | None


def phonetic_words(words: Iterable[tuple[str, str]], dialect: DialectMap = DEFAULT_DIALECT_MAP) -> list[PhoneticWord]:
    """``(id, text)`` pairs to PhoneticWords, in order."""
    out: list[PhoneticWord] = []
    for word_id, text in words:
        try:
            unit: Unit | None = unit_from_text(text, dialect, id=f"bank:{word_id}")
        except PronunciationError:
            unit = None
        out.append(PhoneticWord(id=word_id, text=text, sort_key=text.lower(), unit=unit))
    return out


# a syllable as score() reads it: the full key when its onset counts, the rhyme part when it does not
_SylKey = tuple[object, ...]


@dataclass(frozen=True, slots=True)
class _Tail:
    """A unit's last ``k`` effective syllables, last first, with their memo keys."""

    syllables: tuple[Syllable, ...]
    full: tuple[_SylKey, ...]
    rhyme: tuple[_SylKey, ...]


class PairScorer:
    """Directed scores, exactly ``score(a, b, min(k, syllables(a)))`` (tests check equality).

    ``score`` is a depth-weighted mean of per-syllable ``align_syllable`` totals; the totals are
    memoized per syllable pair (the deepest aligned syllable's onset is not read, so it is keyed on
    its rhyme part only) and combined here in the same order and with the same rules as ``score``.
    Phone distances are symmetric, so a pair's total is shared by both orders. This keeps families
    over a large bank fast: banks repeat syllables far more often than they repeat words.
    """

    def __init__(self, k: int, weights: ScoreWeights = DEFAULT_WEIGHTS) -> None:
        if not 1 <= k <= MAX_K:
            raise ValueError(f"k must be in 1..{MAX_K}, got {k}")
        self._k = k
        self._weights = weights
        self._tails: dict[str, _Tail] = {}
        self._totals: dict[tuple[_SylKey, _SylKey], float] = {}
        self._weight_sums = tuple(sum(DEPTH_WEIGHTS[:n]) for n in range(MAX_K + 1))
        self.computed = 0  # align_syllable calls made (for tests and logs)

    @property
    def k(self) -> int:
        return self._k

    def _tail(self, unit: Unit) -> _Tail:
        found = self._tails.get(unit.id)
        if found is None:
            syllables = tuple(reversed(unit.effective_pronunciation.syllables[-self._k :]))
            found = _Tail(
                syllables=syllables,
                full=tuple((s.onset, s.nucleus, s.coda, s.deletable_coda) for s in syllables),
                rhyme=tuple((s.nucleus, s.coda, s.deletable_coda) for s in syllables),
            )
            self._tails[unit.id] = found
        return found

    def _total(self, a: _Tail, b: _Tail, i: int, include_onset: bool) -> float:
        key = (a.full[i], b.full[i]) if include_onset else (a.rhyme[i], b.rhyme[i])
        found = self._totals.get(key)
        if found is None:
            found = self._totals.get((key[1], key[0]))
        if found is None:
            found = align_syllable(a.syllables[i], b.syllables[i], self._weights, include_onset=include_onset)[3]
            self._totals[key] = found
            self.computed += 1
        return found

    def directed(self, a: Unit, b: Unit) -> PairScore:
        """The score rhyme-suggest gives candidate ``b`` for anchor ``a``."""
        tail_a, tail_b = self._tail(a), self._tail(b)
        padded = len(tail_a.syllables)  # min(k, len(a)); score() pads to this depth
        d = min(padded, len(tail_b.syllables))
        weighted = 0.0
        perfect = True
        match_depth = 0
        counting = True
        for i in range(d):
            total = self._total(tail_a, tail_b, i, i < d - 1)
            weighted += DEPTH_WEIGHTS[i] * total
            perfect = perfect and total == 1.0
            if counting and total >= MATCH_DEPTH_THRESHOLD:
                match_depth += 1
            else:
                counting = False
        value = 1.0 if d == padded and perfect else weighted / self._weight_sums[padded]
        return PairScore(score=max(0.0, min(1.0, value)), match_depth=match_depth)

    def similarity(self, a: Unit, b: Unit) -> float:
        """``max(score(a, b), score(b, a))``. Equal-length tails score the same both ways."""
        forward = self.directed(a, b).score
        if forward >= 1.0 or len(self._tail(a).syllables) == len(self._tail(b).syllables):
            return forward
        return max(forward, self.directed(b, a).score)
