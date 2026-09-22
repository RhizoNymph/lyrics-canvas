"""Near-rhyme scoring: right-to-left syllable alignment with articulatory similarity."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from app.services.phonetics.distance import phone_distance
from app.services.phonetics.errors import RhymeQueryError
from app.services.phonetics.types import Pronunciation, Syllable

DEPTH_WEIGHTS: tuple[float, ...] = (1.0, 0.7, 0.5, 0.35)
MATCH_DEPTH_THRESHOLD = 0.8
MAX_K = len(DEPTH_WEIGHTS)
DELETABLE_COST = 0.25
UNPAIRED_COST = 1.0


@dataclass(frozen=True, slots=True)
class ScoreWeights:
    vowel: float = 1.0
    coda: float = 0.6
    onset: float = 0.25

    def __post_init__(self) -> None:
        if min(self.vowel, self.coda, self.onset) < 0 or self.vowel + self.coda <= 0:
            raise RhymeQueryError("score weights must be non-negative with vowel + coda > 0")


DEFAULT_WEIGHTS = ScoreWeights()


@dataclass(frozen=True, slots=True)
class SyllableAlignment:
    a_index: int | None
    b_index: int | None
    nucleus: float
    coda: float
    onset: float
    total: float


@dataclass(frozen=True, slots=True)
class RhymeScore:
    score: float
    match_depth: int
    alignment: tuple[SyllableAlignment, ...]


ZERO_SCORE = RhymeScore(score=0.0, match_depth=0, alignment=())


def nucleus_similarity(a: Syllable, b: Syllable) -> float:
    return 1.0 - phone_distance(a.nucleus, b.nucleus)


def _paired_cost(a: Sequence[str], b: Sequence[str], *, right_aligned: bool) -> float:
    n = max(len(a), len(b))
    cost = 0.0
    for i in range(n):
        if right_aligned:
            pa = a[len(a) - 1 - i] if i < len(a) else None
            pb = b[len(b) - 1 - i] if i < len(b) else None
        else:
            pa = a[i] if i < len(a) else None
            pb = b[i] if i < len(b) else None
        if pa is None or pb is None:
            cost += UNPAIRED_COST
        else:
            cost += phone_distance(pa, pb)
    return cost


def coda_similarity(a: Syllable, b: Syllable) -> float:
    """Right-aligned coda pairing; the best over dropping dialect-deletable final phones."""
    if a.coda == b.coda:
        return 1.0
    denom = max(len(a.coda), len(b.coda), 1)
    best = 0.0
    for drop_a in range(a.deletable_coda + 1):
        ca = a.coda[: len(a.coda) - drop_a] if drop_a else a.coda
        for drop_b in range(b.deletable_coda + 1):
            cb = b.coda[: len(b.coda) - drop_b] if drop_b else b.coda
            cost = DELETABLE_COST * (drop_a + drop_b) + _paired_cost(ca, cb, right_aligned=True)
            best = max(best, 1.0 - cost / denom)
    return max(0.0, min(1.0, best))


def onset_similarity(a: Syllable, b: Syllable) -> float:
    """Left-aligned onset pairing."""
    if a.onset == b.onset:
        return 1.0
    denom = max(len(a.onset), len(b.onset), 1)
    return max(0.0, 1.0 - _paired_cost(a.onset, b.onset, right_aligned=False) / denom)


def align_syllable(a: Syllable, b: Syllable, weights: ScoreWeights, *, include_onset: bool) -> tuple[float, float, float, float]:
    nuc = nucleus_similarity(a, b)
    cod = coda_similarity(a, b)
    ons = onset_similarity(a, b)
    if include_onset:
        total = (weights.vowel * nuc + weights.coda * cod + weights.onset * ons) / (
            weights.vowel + weights.coda + weights.onset
        )
    else:
        total = (weights.vowel * nuc + weights.coda * cod) / (weights.vowel + weights.coda)
    if nuc == 1.0 and cod == 1.0 and (ons == 1.0 or not include_onset):
        total = 1.0
    return nuc, cod, ons, total


def score(a: Pronunciation, b: Pronunciation, k: int, weights: ScoreWeights = DEFAULT_WEIGHTS) -> RhymeScore:
    """Score how well the tails of two pronunciations rhyme over the last ``k`` syllables.

    Depth ``d = min(k, len(a), len(b))``; syllables align right-to-left. The onset of the
    deepest aligned syllable is not part of the rhyme (cat / bat is perfect). The depth-weighted
    mean runs over ``min(k, max(len(a), len(b)))`` syllables, so a side that is shorter than the
    requested depth leaves its missing syllables scoring zero (hinge / door hinge at k=2 is
    1.0 / 1.7). Equal rhyme keys at that full depth score exactly 1.0; ``d == 0`` scores 0.
    """
    if not 1 <= k <= MAX_K:
        raise RhymeQueryError(f"k must be in 1..{MAX_K}, got {k}")
    sa = a.syllables
    sb = b.syllables
    d = min(k, len(sa), len(sb))
    if d == 0:
        return ZERO_SCORE
    padded_depth = min(k, max(len(sa), len(sb)))
    alignment: list[SyllableAlignment] = []
    weighted = 0.0
    weight_sum = sum(DEPTH_WEIGHTS[:padded_depth])
    for i in range(d):
        ia = len(sa) - 1 - i
        ib = len(sb) - 1 - i
        nuc, cod, ons, total = align_syllable(sa[ia], sb[ib], weights, include_onset=i < d - 1)
        alignment.append(SyllableAlignment(a_index=ia, b_index=ib, nucleus=nuc, coda=cod, onset=ons, total=total))
        weighted += DEPTH_WEIGHTS[i] * total
    match_depth = 0
    for entry in alignment:
        if entry.total >= MATCH_DEPTH_THRESHOLD:
            match_depth += 1
        else:
            break
    value = 1.0 if d == padded_depth and all(e.total == 1.0 for e in alignment) else weighted / weight_sum
    return RhymeScore(score=max(0.0, min(1.0, value)), match_depth=match_depth, alignment=tuple(alignment))
