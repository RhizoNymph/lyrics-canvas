"""In-memory doubles for the rhyme-suggest service and route tests."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

from app.schemas.phonetics import SnippetPhonetics
from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.index import RhymeIndex
from app.services.phonetics.score import ScoreWeights
from app.services.phonetics.sources.base import Candidate, SourceName, suggest_from_index
from app.services.phonetics.types import Provenance, Unit
from app.services.phonetics.units import dict_unit_id, unit_from_text
from app.services.phonetics_service import compute_snippet_phonetics
from app.services.rhyme_suggest_service import SnippetRecord

DICT_WORDS = (
    "cat", "bat", "hat", "cap", "cash", "combat", "acrobat", "dog", "orange", "hinge", "syringe",
    "station", "nation", "motion", "letter", "better", "tomorrow", "sorrow", "borrow",
    "ring", "thin", "sing", "test", "mess", "light", "night", "bright", "tonight", "tree", "blue",
)


class FakeDictionary:
    name: SourceName = "dictionary"

    def __init__(self, dialect: DialectMap, weights: ScoreWeights | None = None, include_rare: bool = False, words=DICT_WORDS):
        self.index = RhymeIndex(dialect)
        self.weights = weights
        self.include_rare = include_rare
        for w in words:
            self.index.insert(unit_from_text(w, dialect, id=dict_unit_id(w), provenance=Provenance.dictionary, ephemeral=False))

    def suggest(self, anchors: Sequence[Unit], k: int, min_score: float, limit: int, exclude: frozenset[str]) -> list[Candidate]:
        return suggest_from_index(self.index, self.name, anchors, k, min_score, limit, exclude, self.weights)


def make_record(snippet_id: str, text: str, tags: Sequence[str] = (), canvas_id: str = "c1", with_phonetics: bool = True) -> SnippetRecord:
    grid = syllabify_text(text)
    phonetics = compute_snippet_phonetics(text, grid, None).model_dump(mode="json") if with_phonetics else None
    return SnippetRecord(id=snippet_id, canvas_id=canvas_id, raw_text=text, grid_data=grid, phonetics=phonetics, tag_ids=tuple(tags))


@dataclass
class FakeRepository:
    canvases: dict[str, str] = field(default_factory=dict)  # canvas_id -> owner
    snippets: dict[str, SnippetRecord] = field(default_factory=dict)

    async def canvas_owned(self, canvas_id: str, user_id: str) -> bool:
        return self.canvases.get(canvas_id) == user_id

    async def snippet(self, snippet_id: str, user_id: str) -> SnippetRecord | None:
        record = self.snippets.get(snippet_id)
        if record is None or self.canvases.get(record.canvas_id) != user_id:
            return None
        return record

    async def user_snippets(self, user_id: str) -> list[SnippetRecord]:
        return [r for r in self.snippets.values() if self.canvases.get(r.canvas_id) == user_id and r.raw_text]


def default_repo() -> FakeRepository:
    repo = FakeRepository(canvases={"c1": "u1", "c2": "u1", "c-other": "u2"})
    repo.snippets = {
        "s-cat": make_record("s-cat", "I saw a black cat\nsleeping on the mat", tags=("t-animals",)),
        "s-hat": make_record("s-hat", "wearing my hat", tags=("t-clothes", "t-animals")),
        "s-dog": make_record("s-dog", "walking the dog"),
        "s-nation": make_record("s-nation", "all across the nation", canvas_id="c2"),
        "s-raw": make_record("s-raw", "another black hat", with_phonetics=False),
        "s-empty": make_record("s-empty", ""),
        "s-other": make_record("s-other", "cat", canvas_id="c-other"),
    }
    return repo


def phonetics_of(record: SnippetRecord) -> SnippetPhonetics:
    assert record.phonetics is not None
    return SnippetPhonetics.model_validate(record.phonetics)
