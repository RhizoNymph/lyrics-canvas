"""Notes source: a per-request RhymeIndex over the requesting user's snippet line units."""

from __future__ import annotations

from typing import Iterable, Sequence

from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.index import RhymeIndex
from app.services.phonetics.score import ScoreWeights
from app.services.phonetics.sources.base import Candidate, SourceName, suggest_from_index
from app.services.phonetics.types import Unit


class NotesSource:
    name: SourceName = "notes"

    def __init__(self, units: Iterable[Unit], dialect: DialectMap, weights: ScoreWeights | None = None) -> None:
        self._index = RhymeIndex(dialect)
        self._weights = weights
        for unit in units:
            self._index.insert(unit)

    def __len__(self) -> int:
        return len(self._index)

    @property
    def index(self) -> RhymeIndex:
        return self._index

    def suggest(
        self, anchors: Sequence[Unit], k: int, min_score: float, limit: int, exclude: frozenset[str]
    ) -> list[Candidate]:
        return suggest_from_index(self._index, self.name, anchors, k, min_score, limit, exclude, self._weights)
