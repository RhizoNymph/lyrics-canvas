import csv
import io
import uuid
from dataclasses import dataclass, field

from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics import DEFAULT_DIALECT_MAP
from app.services.phonetics.dialect import DialectMap
from scripts.backfill_phonetics import ReportRow, backfill_snippet, load_dialect, write_report


@dataclass
class FakeSnippet:
    raw_text: str
    grid_data: dict | None
    phonetics: dict | None = None
    id: uuid.UUID = field(default_factory=uuid.uuid4)


def test_backfill_sets_phonetics_and_reports_disagreements():
    text = "hello world"
    grid = syllabify_text(text)
    grid["lines"][0]["cells"][0]["stress"] = 1
    grid["lines"][0]["cells"][1]["stress"] = 0
    snippet = FakeSnippet(raw_text=text, grid_data=grid)
    rows = backfill_snippet(snippet, DEFAULT_DIALECT_MAP, force=False)
    assert snippet.phonetics is not None
    assert snippet.phonetics["lines"][0]["stress"] == "101"
    assert "0:0" in snippet.phonetics["overrides"]
    assert rows == [ReportRow(snippet_id=str(snippet.id), line=0, word=0, grid_stress="10", engine_stress="01")]


def test_backfill_is_idempotent_unless_forced():
    snippet = FakeSnippet(raw_text="hello world", grid_data=syllabify_text("hello world"))
    assert backfill_snippet(snippet, DEFAULT_DIALECT_MAP, force=False) == []
    first = snippet.phonetics
    assert first is not None
    assert backfill_snippet(snippet, DEFAULT_DIALECT_MAP, force=False) is None
    assert snippet.phonetics is first
    assert backfill_snippet(snippet, DEFAULT_DIALECT_MAP, force=True) == []
    assert snippet.phonetics == first


def test_backfill_handles_empty_snippets_and_missing_grid():
    empty = FakeSnippet(raw_text="", grid_data={"lines": []})
    assert backfill_snippet(empty, DEFAULT_DIALECT_MAP, force=False) == []
    assert empty.phonetics == {"dialect_digest": DEFAULT_DIALECT_MAP.digest(), "lines": [], "overrides": {}}
    no_grid = FakeSnippet(raw_text="hello world", grid_data=None)
    assert backfill_snippet(no_grid, DEFAULT_DIALECT_MAP, force=False) == []
    assert no_grid.phonetics["lines"][0]["stress"] == "011"


def test_write_report_csv():
    buffer = io.StringIO()
    write_report(buffer, [ReportRow("s1", 0, 1, "10", "01"), ReportRow("s2", 2, 0, "1", "01")])
    reader = list(csv.DictReader(io.StringIO(buffer.getvalue())))
    assert reader == [
        {"snippet_id": "s1", "line": "0", "word": "1", "grid_stress": "10", "engine_stress": "01"},
        {"snippet_id": "s2", "line": "2", "word": "0", "grid_stress": "1", "engine_stress": "01"},
    ]


def test_load_dialect(tmp_path):
    assert load_dialect(None) == DEFAULT_DIALECT_MAP
    path = tmp_path / "dialect.json"
    path.write_text('{"rules": [{"kind": "merge", "source": ["AO"], "target": ["AA"]}]}')
    loaded = load_dialect(path)
    assert isinstance(loaded, DialectMap)
    assert len(loaded.rules) == 1
