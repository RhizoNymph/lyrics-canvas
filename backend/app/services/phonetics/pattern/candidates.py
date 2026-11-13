"""Slot candidates: units of a RhymeIndex that rhyme with every member of a slot."""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from app.services.phonetics.index import RhymeIndex
from app.services.phonetics.score import RhymeScore, ScoreWeights
from app.services.phonetics.types import Unit

SOURCE_LIMIT = 5000  # per member query, as rhyme-suggest's per-source cap


@dataclass(frozen=True, slots=True)
class SlotCandidate:
    """``scores[i]`` is the unit's score against member ``i`` (member order)."""

    unit: Unit
    scores: tuple[RhymeScore, ...]

    @property
    def weakest(self) -> int:
        """Index of the member it rhymes with least (the first one on ties)."""
        return min(range(len(self.scores)), key=lambda i: (self.scores[i].score, i))

    @property
    def score(self) -> float:
        return self.scores[self.weakest].score

    @property
    def mean(self) -> float:
        return sum(s.score for s in self.scores) / len(self.scores)

    @property
    def match_depth(self) -> int:
        return self.scores[self.weakest].match_depth


def _base(unit: Unit):
    return unit.pronunciation_override if unit.pronunciation_override is not None else unit.pronunciation


def slot_candidates(
    index: RhymeIndex,
    members: Sequence[Unit],
    k: int,
    min_score: float,
    exclude: frozenset[str] = frozenset(),
    weights: ScoreWeights | None = None,
    limit: int = SOURCE_LIMIT,
) -> list[SlotCandidate]:
    """Query the index with each member's tail at depth ``k`` and keep the units every query
    returned (each scored at least ``min_score`` against every member), by unit id."""
    per_member: list[dict[str, RhymeScore]] = []
    for member in members:
        hits = index.query(_base(member), k=k, min_score=min_score, limit=limit, exclude=exclude, weights=weights)
        per_member.append({hit.unit_id: hit.score for hit in hits})
        if not per_member[-1]:
            return []
    common = set(per_member[0]).intersection(*per_member[1:])
    found: list[SlotCandidate] = []
    for unit_id in sorted(common):
        unit = index.get(unit_id)
        if unit is not None:
            found.append(SlotCandidate(unit=unit, scores=tuple(scores[unit_id] for scores in per_member)))
    return found
