"""In-memory doubles for the rhyme-pattern service and route tests."""

from __future__ import annotations

from dataclasses import dataclass, field, replace

from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.index import RhymeIndex
from app.services.phonetics.types import Provenance
from app.services.phonetics.units import dict_unit_id, unit_from_text
from tests.fakes import FakeRepository, make_record

from .helpers import EXAMPLE

PATTERN_DICT_WORDS = (
    "steady", "ready", "heavy", "already", "petty", "sweaty", "jetty",
    "pressure", "measure", "treasure", "weather", "better", "letter", "feather", "never",
    "on", "wrong", "long", "strong", "song", "along", "gone",
    "cat", "hat", "dog", "tree",
)


def fake_dictionary_index(dialect: DialectMap, include_rare: bool, words: tuple[str, ...] = PATTERN_DICT_WORDS) -> RhymeIndex:
    index = RhymeIndex(dialect)
    for w in words:
        index.insert(unit_from_text(w, dialect, id=dict_unit_id(w), provenance=Provenance.dictionary, ephemeral=False))
    return index


@dataclass
class FakePatternRepository(FakeRepository):
    placements: dict[str, set[str]] = field(default_factory=dict)  # snippet_id -> canvas ids

    async def snippet_on_canvas(self, snippet_id: str, canvas_id: str, user_id: str) -> bool:
        return self.canvases.get(canvas_id) == user_id and canvas_id in self.placements.get(snippet_id, set())


VERSE = "\n".join((*EXAMPLE, "I hold my letter in the rain"))
NOTES = "\n".join(("heavy is the feather song", "long ago so steady", "she was petty so I measure strong"))


def _record(snippet_id: str, text: str, canvas_id: str = "c1", tags: tuple[str, ...] = (), names: tuple[str, ...] = ()):
    return replace(make_record(snippet_id, text, tags=tags, canvas_id=canvas_id), tag_names=names)


def pattern_repo() -> FakePatternRepository:
    repo = FakePatternRepository(canvases={"c1": "u1", "c2": "u1", "c-other": "u2"})
    records = [
        (_record("s-verse", VERSE), {"c1"}),
        (_record("s-notes", NOTES, tags=("t-hook",), names=("hook",)), {"c1"}),
        (_record("s-draft", "a treasure gone along", canvas_id="c2", tags=("t-draft",), names=("draft",)), {"c2"}),
        (_record("s-punct", "Keep it Steady!\nnow READY"), {"c1"}),
        (_record("s-short", "steady\n_"), {"c1"}),
        (_record("s-long", "\n".join(["steady weather"] * 17)), {"c1"}),
        (_record("s-digits", "steady 42\nready 7"), {"c1"}),
        (_record("s-elsewhere", "steady\nready", canvas_id="c2"), {"c2"}),
        (_record("s-foreign", "steady\nready", canvas_id="c-other"), {"c-other"}),
    ]
    for record, canvases in records:
        repo.snippets[record.id] = record
        repo.placements[record.id] = canvases
    return repo
