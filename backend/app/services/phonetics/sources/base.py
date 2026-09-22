"""Candidate sources: thin adapters that run a RhymeIndex for a set of anchors."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, Protocol, Sequence

from app.services.phonetics.index import RhymeIndex
from app.services.phonetics.score import RhymeScore, ScoreWeights
from app.services.phonetics.types import Unit

SourceName = Literal["notes", "dictionary"]


@dataclass(frozen=True, slots=True)
class Candidate:
    unit: Unit
    score: RhymeScore
    best_anchor_id: str
    source: SourceName


class CandidateSource(Protocol):
    name: SourceName

    def suggest(
        self, anchors: Sequence[Unit], k: int, min_score: float, limit: int, exclude: frozenset[str]
    ) -> list[Candidate]: ...


def suggest_from_index(
    index: RhymeIndex,
    name: SourceName,
    anchors: Sequence[Unit],
    k: int,
    min_score: float,
    limit: int,
    exclude: frozenset[str],
    weights: ScoreWeights | None = None,
) -> list[Candidate]:
    """Query the index once per anchor, keep the best anchor per unit, rank, cap at ``limit``."""
    best: dict[str, Candidate] = {}
    for anchor in anchors:
        base = anchor.pronunciation_override if anchor.pronunciation_override is not None else anchor.pronunciation
        for hit in index.query(base, k=k, min_score=min_score, limit=limit, exclude=exclude, weights=weights):
            current = best.get(hit.unit_id)
            if current is None or (hit.score.score, hit.score.match_depth) > (current.score.score, current.score.match_depth):
                unit = index.get(hit.unit_id)
                if unit is None:
                    continue
                best[hit.unit_id] = Candidate(unit=unit, score=hit.score, best_anchor_id=anchor.id, source=name)
    ranked = sorted(best.values(), key=lambda c: (-c.score.score, -c.score.match_depth, c.unit.id))
    return ranked[:limit]
