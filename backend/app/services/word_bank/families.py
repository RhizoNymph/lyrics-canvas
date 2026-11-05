"""``POST /api/word-bank/families``: group bank words into rhyme families. Pure and deterministic.

1. Pairwise similarity ``s(a, b) = max(score(a, b), score(b, a))`` (app/services/word_bank/phonetic.py).
2. Average-linkage agglomerative clustering: repeatedly merge the most similar pair of clusters
   while their average similarity is >= ``min_score``; ties go to the pair whose clusters' lowest
   ``(lower(text), id)`` keys sort first.

A cluster of two or more words is a family; the rest (and words with no pronunciation) are
``unmatched``. A family's medoid has the highest mean similarity to the rest (ties: lower(text));
it gives the ``label`` and ``rhyme_key`` and comes first in ``words``, followed by similarity to
the medoid desc, then lower(text). ``cohesion`` is the mean pairwise similarity. Families sort by
size desc, cohesion desc, lower(label); ``unmatched`` by lower(text).
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

import numpy as np

from app.services.phonetics.types import RhymeKey, Unit
from app.services.word_bank.errors import TooManyWordsError
from app.services.word_bank.phonetic import PhoneticWord

MAX_FAMILY_WORDS = 1000


class Similarity(Protocol):
    def similarity(self, a: Unit, b: Unit) -> float: ...


@dataclass(frozen=True, slots=True)
class Family:
    label: str
    rhyme_key: RhymeKey
    words: tuple[PhoneticWord, ...]  # medoid first
    cohesion: float


@dataclass(frozen=True, slots=True)
class FamiliesResult:
    families: tuple[Family, ...]
    unmatched: tuple[PhoneticWord, ...]


def _key(word: PhoneticWord) -> tuple[str, str]:
    return (word.sort_key, word.id)


@dataclass(frozen=True, slots=True)
class _Voiced:
    """A pronounceable word (its unit is set)."""

    word: PhoneticWord
    unit: Unit


def _similarity_matrix(words: Sequence[_Voiced], scorer: Similarity) -> np.ndarray:
    n = len(words)
    matrix = np.zeros((n, n), dtype=np.float64)
    for i in range(n):
        for j in range(i + 1, n):
            matrix[i, j] = matrix[j, i] = scorer.similarity(words[i].unit, words[j].unit)
    np.fill_diagonal(matrix, 1.0)
    return matrix


def _cluster(words: Sequence[_Voiced], base: np.ndarray, min_score: float) -> list[list[int]]:
    """Average-linkage clusters (lists of indices into ``words``), Lance-Williams updates."""
    n = len(words)
    members: dict[int, list[int]] = {i: [i] for i in range(n)}
    keys: dict[int, tuple[str, str]] = {i: _key(words[i].word) for i in range(n)}
    linkage = base.copy()
    np.fill_diagonal(linkage, -np.inf)
    while len(members) > 1:
        flat = linkage.ravel()
        best = float(flat.max())
        if best < min_score:
            break
        # every pair at the best average, each once (row < column; the matrix is symmetric)
        ties = [(int(x) // n, int(x) % n) for x in np.flatnonzero(flat == best)]
        i, j = min(
            ((r, c) for r, c in ties if r < c),
            key=lambda p: tuple(sorted((keys[p[0]], keys[p[1]]))),
        )
        size_i, size_j = len(members[i]), len(members[j])
        merged = (size_i * linkage[i] + size_j * linkage[j]) / (size_i + size_j)
        linkage[i, :] = merged
        linkage[:, i] = merged
        linkage[j, :] = -np.inf
        linkage[:, j] = -np.inf
        linkage[i, i] = -np.inf
        members[i].extend(members.pop(j))
        keys[i] = min(keys[i], keys.pop(j))
    return list(members.values())


def _family(indices: list[int], words: Sequence[_Voiced], base: np.ndarray) -> Family:
    sub = base[np.ix_(indices, indices)]
    size = len(indices)
    means = (sub.sum(axis=1) - 1.0) / (size - 1)  # the diagonal is 1.0
    medoid_pos = min(range(size), key=lambda p: (-float(means[p]), _key(words[indices[p]].word)))
    medoid = words[indices[medoid_pos]]
    rest = sorted(
        (p for p in range(size) if p != medoid_pos),
        key=lambda p: (-float(sub[medoid_pos, p]), _key(words[indices[p]].word)),
    )
    cohesion = float((sub.sum() - size) / (size * (size - 1)))
    return Family(
        label=medoid.word.text,
        rhyme_key=medoid.unit.rhyme_key,
        words=(medoid.word, *(words[indices[p]].word for p in rest)),
        cohesion=cohesion,
    )


def rhyme_families(words: Sequence[PhoneticWord], scorer: Similarity, *, min_score: float) -> FamiliesResult:
    """Every word lands in exactly one family or in ``unmatched``. More than MAX_FAMILY_WORDS
    words raise TooManyWordsError before anything is scored."""
    if len(words) > MAX_FAMILY_WORDS:
        raise TooManyWordsError(f"families work on at most {MAX_FAMILY_WORDS} words; narrow the filter ({len(words)} match)")
    voiced = sorted((_Voiced(w, w.unit) for w in words if w.unit is not None), key=lambda v: _key(v.word))
    unmatched = [w for w in words if w.unit is None]
    families: list[Family] = []
    if voiced:
        base = _similarity_matrix(voiced, scorer)
        for indices in _cluster(voiced, base, min_score):
            if len(indices) == 1:
                unmatched.append(voiced[indices[0]].word)
            else:
                families.append(_family(indices, voiced, base))
    families.sort(key=lambda f: (-len(f.words), -f.cohesion, f.label.lower(), f.label))
    unmatched.sort(key=_key)
    return FamiliesResult(families=tuple(families), unmatched=tuple(unmatched))
