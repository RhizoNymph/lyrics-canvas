"""Rhyme index: hash buckets over rhyme keys with vowel-skeleton fuzzy lookup.

For every unit and each depth ``d`` in 1..4 the unit joins an exact bucket keyed by
``rhyme_keys[d]``; each exact key joins skeleton buckets keyed by its last ``j`` nuclei for
``j`` in 1..d. A query walks the skeleton buckets of the tail's nuclei and their vowel
neighbours, for every ``j`` whose depth-weight mass can still reach ``min_score``, scores
each distinct exact key once (all members of a key bucket share the same tail and therefore
the same score), filters by ``min_score`` and ranks by score, match depth, id.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from itertools import product

from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.distance import vowel_neighbours
from app.services.phonetics.errors import RhymeIndexError, RhymeQueryError
from app.services.phonetics.score import DEFAULT_WEIGHTS, DEPTH_WEIGHTS, MAX_K, RhymeScore, ScoreWeights, score
from app.services.phonetics.types import Pronunciation, PronunciationSource, RhymeKey, Syllable, Unit, WordPronunciation


@dataclass(frozen=True, slots=True)
class IndexHit:
    unit_id: str
    score: RhymeScore


@dataclass(slots=True)
class _Bucket:
    tail: Pronunciation  # the last d syllables, deepest onset stripped
    unit_ids: set[str]


def _representative_tail(syllables: tuple[Syllable, ...], depth: int) -> Pronunciation:
    tail = list(syllables[-depth:])
    tail[0] = replace(tail[0], onset=())
    return Pronunciation(words=(WordPronunciation("", tuple(tail), PronunciationSource.override),))


def min_skeleton_depth(depth: int, min_score: float) -> int:
    """Smallest ``j`` such that perfectly matching the last ``j`` of ``depth`` syllables can reach ``min_score``."""
    total = sum(DEPTH_WEIGHTS[:depth])
    for j in range(1, depth + 1):
        if sum(DEPTH_WEIGHTS[:j]) / total >= min_score:
            return j
    return depth


def _rebase(result: RhymeScore, offset: int) -> RhymeScore:
    if offset == 0:
        return result
    return RhymeScore(
        score=result.score,
        match_depth=result.match_depth,
        alignment=tuple(replace(a, b_index=(a.b_index + offset) if a.b_index is not None else None) for a in result.alignment),
    )


class RhymeIndex:
    def __init__(self, dialect: DialectMap, weights: ScoreWeights = DEFAULT_WEIGHTS) -> None:
        self._dialect = dialect
        self._digest = dialect.digest()
        self._weights = weights
        self._units: dict[str, Unit] = {}
        self._buckets: list[dict[RhymeKey, _Bucket]] = [{} for _ in range(MAX_K)]
        # skeleton[d-1][j-1]: last j nuclei -> depth-d keys
        self._skeleton: list[list[dict[tuple[str, ...], set[RhymeKey]]]] = [
            [{} for _ in range(MAX_K)] for _ in range(MAX_K)
        ]

    @property
    def dialect(self) -> DialectMap:
        return self._dialect

    @property
    def weights(self) -> ScoreWeights:
        return self._weights

    def __len__(self) -> int:
        return len(self._units)

    def __contains__(self, unit_id: str) -> bool:
        return unit_id in self._units

    def get(self, unit_id: str) -> Unit | None:
        return self._units.get(unit_id)

    def insert(self, unit: Unit) -> None:
        if unit.dialect_digest != self._digest:
            raise RhymeIndexError(f"unit {unit.id!r} was built with dialect {unit.dialect_digest[:8]}, index uses {self._digest[:8]}")
        syllables = unit.effective_pronunciation.syllables
        if not syllables:
            raise RhymeIndexError(f"unit {unit.id!r} has no syllables")
        if unit.id in self._units:
            self.remove(unit.id)
        self._units[unit.id] = unit
        for d in range(1, min(MAX_K, len(syllables)) + 1):
            key = unit.rhyme_keys[d]
            bucket = self._buckets[d - 1].get(key)
            if bucket is None:
                bucket = _Bucket(tail=_representative_tail(syllables, d), unit_ids=set())
                self._buckets[d - 1][key] = bucket
                nuclei = tuple(s.nucleus for s in syllables[-d:])
                for j in range(1, d + 1):
                    self._skeleton[d - 1][j - 1].setdefault(nuclei[-j:], set()).add(key)
            bucket.unit_ids.add(unit.id)

    def remove(self, unit_id: str) -> None:
        unit = self._units.pop(unit_id, None)
        if unit is None:
            return
        syllables = unit.effective_pronunciation.syllables
        for d in range(1, min(MAX_K, len(syllables)) + 1):
            key = unit.rhyme_keys[d]
            bucket = self._buckets[d - 1].get(key)
            if bucket is None:
                continue
            bucket.unit_ids.discard(unit_id)
            if not bucket.unit_ids:
                del self._buckets[d - 1][key]
                nuclei = tuple(s.nucleus for s in syllables[-d:])
                for j in range(1, d + 1):
                    skeleton = self._skeleton[d - 1][j - 1]
                    keys = skeleton.get(nuclei[-j:])
                    if keys is not None:
                        keys.discard(key)
                        if not keys:
                            del skeleton[nuclei[-j:]]

    def _candidate_keys(self, nuclei: tuple[str, ...], depth: int, min_score: float) -> set[RhymeKey]:
        keys: set[RhymeKey] = set()
        j = min_skeleton_depth(depth, min_score)
        skeleton = self._skeleton[depth - 1][j - 1]
        for variant in product(*(vowel_neighbours(n) for n in nuclei[-j:])):
            found = skeleton.get(variant)
            if found:
                keys.update(found)
        return keys

    def query(
        self,
        tail: Pronunciation,
        k: int,
        min_score: float,
        limit: int,
        exclude: frozenset[str] = frozenset(),
        weights: ScoreWeights | None = None,
    ) -> list[IndexHit]:
        if not 1 <= k <= MAX_K:
            raise RhymeQueryError(f"k must be in 1..{MAX_K}, got {k}")
        if limit < 1:
            raise RhymeQueryError(f"limit must be positive, got {limit}")
        if not 0.0 <= min_score <= 1.0:
            raise RhymeQueryError(f"min_score must be in [0, 1], got {min_score}")
        weights = weights or self._weights
        effective = self._dialect.apply(tail)
        tail_syllables = effective.syllables
        if not tail_syllables:
            return []
        k_eff = min(k, len(tail_syllables))
        scored: list[tuple[float, int, str, RhymeScore, int]] = []
        for d in range(1, k_eff + 1):
            nuclei = tuple(s.nucleus for s in tail_syllables[-d:])
            buckets = self._buckets[d - 1]
            for key in self._candidate_keys(nuclei, d, min_score):
                bucket = buckets[key]
                members = [
                    uid
                    for uid in bucket.unit_ids
                    if uid not in exclude
                    and ((self._units[uid].syllable_count == d) if d < k_eff else (self._units[uid].syllable_count >= k_eff))
                ]
                if not members:
                    continue
                # Score at the requested depth, not the bucket depth: members with fewer
                # than k_eff syllables (d < k_eff) must take the short-side discount that
                # score() applies, exactly as a direct score(anchor, candidate, k) would.
                result = score(effective, bucket.tail, k_eff, weights)
                if result.score < min_score:
                    continue
                for uid in members:
                    scored.append((-result.score, -result.match_depth, uid, result, self._units[uid].syllable_count - d))
        scored.sort(key=lambda item: (item[0], item[1], item[2]))
        return [IndexHit(unit_id=uid, score=_rebase(result, offset)) for _, _, uid, result, offset in scored[:limit]]
