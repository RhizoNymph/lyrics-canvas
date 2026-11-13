"""Pairwise span scoring for detection, cached by rhyme tail.

``pair(a, b)`` scores the two spans' effective pronunciations at ``k = min(a.k, b.k)`` with the
engine's ``score``. Both spans have at least ``k`` syllables, so the result depends only on their
last ``k`` syllables (the deepest onset is not part of the rhyme). Each span gets a
``TailSignature``: its ``k`` and an interned id of its tail at every depth ``1..k``; spans with
equal signatures score alike against everything, and results are cached per pair of tail ids.
Pairs whose last ``j`` nuclei are not vowel neighbours are skipped exactly as
``RhymeIndex.query`` skips them (``j = min_skeleton_depth(k, min_score)``), so detection and
suggestion agree on what can rhyme.
"""

from __future__ import annotations

from dataclasses import dataclass, replace

from app.services.phonetics.distance import vowel_neighbours
from app.services.phonetics.index import min_skeleton_depth
from app.services.phonetics.pattern.types import PatternSpan
from app.services.phonetics.score import DEFAULT_WEIGHTS, MAX_K, RhymeScore, ScoreWeights, score
from app.services.phonetics.types import Pronunciation, PronunciationSource, Syllable, WordPronunciation

Tail = tuple[Syllable, ...]


@dataclass(frozen=True, slots=True, eq=False)
class TailSignature:
    """``tails[d - 1]`` is the interned id of the last ``d`` syllables (deepest onset stripped);
    ``nuclei[d - 1]`` the nucleus ``d`` syllables from the end. Interned per ``PairScorer`` (one
    object per ``(k, tails)``), so identity is equality and hashing is cheap."""

    k: int
    tails: tuple[int, ...]
    nuclei: tuple[str, ...]


def _tail(syllables: tuple[Syllable, ...], depth: int) -> Tail:
    tail = list(syllables[-depth:])
    tail[0] = replace(tail[0], onset=())
    return tuple(tail)


def _as_pron(tail: Tail) -> Pronunciation:
    return Pronunciation(words=(WordPronunciation("", tail, PronunciationSource.override),))


class PairScorer:
    """Scores span pairs at ``min(k_a, k_b)``; ``pair`` returns None below ``min_score``."""

    def __init__(self, min_score: float, weights: ScoreWeights | None = None) -> None:
        self._min_score = min_score
        self._weights = weights or DEFAULT_WEIGHTS
        self._tail_ids: dict[Tail, int] = {}
        self._tails: list[Tail] = []
        self._cache: dict[tuple[int, int], RhymeScore] = {}
        self._signatures: dict[str, TailSignature] = {}
        self._interned: dict[tuple[int, tuple[int, ...]], TailSignature] = {}
        self._pairs: dict[tuple[TailSignature, TailSignature], float | None] = {}
        self._skeleton = [min_skeleton_depth(k, min_score) for k in range(1, MAX_K + 1)]

    @property
    def min_score(self) -> float:
        return self._min_score

    def _intern(self, tail: Tail) -> int:
        found = self._tail_ids.get(tail)
        if found is None:
            found = len(self._tails)
            self._tail_ids[tail] = found
            self._tails.append(tail)
        return found

    def signature(self, span: PatternSpan) -> TailSignature:
        found = self._signatures.get(span.unit.id)
        if found is not None:
            return found
        syllables = span.unit.effective_pronunciation.syllables
        depth = min(span.k, len(syllables))
        tails = tuple(self._intern(_tail(syllables, d)) for d in range(1, depth + 1))
        sig = self._interned.get((span.k, tails))
        if sig is None:
            sig = TailSignature(k=span.k, tails=tails, nuclei=tuple(syllables[-d].nucleus for d in range(1, depth + 1)))
            self._interned[(span.k, tails)] = sig
        self._signatures[span.unit.id] = sig
        return sig

    def _scored(self, ta: int, tb: int, k: int) -> RhymeScore:
        key = (ta, tb) if ta <= tb else (tb, ta)  # score() is symmetric
        found = self._cache.get(key)
        if found is None:
            found = score(_as_pron(self._tails[key[0]]), _as_pron(self._tails[key[1]]), k, self._weights)
            self._cache[key] = found
        return found

    def pair_signatures(self, a: TailSignature, b: TailSignature) -> float | None:
        """The pair score at ``min(a.k, b.k)`` when it reaches ``min_score``, else None."""
        key = (a, b)
        if key in self._pairs:
            return self._pairs[key]
        found = self._pair_uncached(a, b)
        self._pairs[key] = found
        self._pairs[(b, a)] = found
        return found

    def _pair_uncached(self, a: TailSignature, b: TailSignature) -> float | None:
        k = min(a.k, b.k)
        for d in range(min(self._skeleton[k - 1], k)):
            if b.nuclei[d] not in vowel_neighbours(a.nuclei[d]):
                return None
        result = self._scored(a.tails[k - 1], b.tails[k - 1], k).score
        return result if result >= self._min_score else None

    def pair(self, a: PatternSpan, b: PatternSpan) -> float | None:
        return self.pair_signatures(self.signature(a), self.signature(b))

    def at(self, a: PatternSpan, b: PatternSpan, k: int) -> RhymeScore:
        """``score(a, b, k)`` on the effective pronunciations (cached when both have ``k`` syllables)."""
        sa, sb = self.signature(a), self.signature(b)
        if k > min(len(sa.tails), len(sb.tails)):
            return score(a.unit.effective_pronunciation, b.unit.effective_pronunciation, k, self._weights)
        return self._scored(sa.tails[k - 1], sb.tails[k - 1], k)
