"""Every backend reader of snippet text treats ``_`` as a rest: the syllabifier emits rest cells,
and phonetics, rhyme, merge, export and the per-word slices read word tokens or display text."""

from __future__ import annotations

from types import SimpleNamespace

from app.models.snippet import SnippetType
from app.schemas.merge import MergeMode
from app.schemas.phonetics import PronunciationOverride, SyllableModel
from app.schemas.rhyme import TextAnchor
from app.services.export_service import export_as_markdown, export_as_pdf, export_as_text
from app.services.merge_service import merge_snippets, word_count
from app.services.nlp.rhyme_service import find_rhymes_in_texts
from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics import DEFAULT_DIALECT_MAP
from app.services.phonetics_service import compute_snippet_phonetics, note_units_from_phonetics
from app.services.rhyme_suggest_service import resolve_anchor, slice_grid_line
from tests.merge_fixtures import grid, source

REST = {"rest": True}


# ---- syllabify_text ----


def test_rest_mark_becomes_a_rest_cell_and_word_indexes_skip_it():
    line = syllabify_text("I _ go")["lines"][0]
    assert line["raw_text"] == "I _ go"
    assert line["syllable_count"] == 2
    assert line["cells"] == [
        {"syllable": "i", "stress": 1, "word_index": 0},
        REST,
        {"syllable": "go", "stress": 1, "word_index": 1},
    ]


def test_rest_cells_have_only_the_rest_key():
    cells = syllabify_text("_go__now_")["lines"][0]["cells"]
    rests = [c for c in cells if c.get("rest")]
    assert len(rests) == 4
    assert all(c == REST for c in rests)
    assert [c.get("word_index") for c in cells] == [None, 0, None, None, 1, None]


def test_underscore_splits_words():
    cells = syllabify_text("go_now")["lines"][0]["cells"]
    assert [c.get("syllable") for c in cells] == ["go", None, "now"]
    assert [c.get("word_index") for c in cells] == [0, None, 1]


def test_rest_only_line_is_a_line_of_rest_cells():
    lines = syllabify_text("hello\n_ _\nworld")["lines"]
    assert len(lines) == 3
    assert lines[1] == {"cells": [REST, REST], "syllable_count": 0, "raw_text": "_ _"}


def test_blank_lines_are_still_skipped():
    lines = syllabify_text("a\n\n  \nb")["lines"]
    assert [line["raw_text"] for line in lines] == ["a", "b"]


def test_raw_text_is_the_trimmed_line():
    assert syllabify_text("   _ yeah _  ")["lines"][0]["raw_text"] == "_ yeah _"


def test_unpronounceable_word_still_takes_a_word_index():
    cells = syllabify_text("42 _ go")["lines"][0]["cells"]
    assert cells == [REST, {"syllable": "go", "stress": 1, "word_index": 1}]


# ---- phonetics ----


def test_phonetics_words_skip_rests_and_lines_match_the_grid():
    text = "I _ go\n_ _\nhome_now"
    phonetics = compute_snippet_phonetics(text, syllabify_text(text), None)
    assert [line.line_index for line in phonetics.lines] == [0, 1, 2]
    assert [w.word for w in phonetics.lines[0].words] == ["i", "go"]
    assert phonetics.lines[1].words == []
    assert phonetics.lines[1].syllable_count == 0
    assert [w.word for w in phonetics.lines[2].words] == ["home", "now"]
    assert phonetics.overrides == {}


def test_grid_stress_override_keys_count_words_only():
    text = "I _ hello"
    grid_data = syllabify_text(text)
    cells = grid_data["lines"][0]["cells"]
    hello = [c for c in cells if c.get("word_index") == 1]
    hello[0]["stress"], hello[1]["stress"] = 1, 0
    phonetics = compute_snippet_phonetics(text, grid_data, None)
    assert set(phonetics.overrides) == {"0:1"}
    assert [s.stress for s in phonetics.lines[0].words[1].syllables] == [1, 0]


def test_existing_override_keyed_by_word_index_still_applies_after_rests_are_typed():
    custom = PronunciationOverride(syllables=[SyllableModel(onset=["K"], nucleus="AA", coda=["T"], stress=1)])
    text = "big _ _ dog"
    phonetics = compute_snippet_phonetics(text, syllabify_text(text), {"0:1": custom})
    assert phonetics.lines[0].words[1].source == "override"
    assert phonetics.lines[0].words[1].syllables[0].nucleus == "AA"


def test_note_units_have_no_rest_text():
    text = "black _ cat\n_"
    phonetics = compute_snippet_phonetics(text, syllabify_text(text), None)
    units = note_units_from_phonetics("s1", phonetics, DEFAULT_DIALECT_MAP)
    assert sorted(u.text for u in units) == ["black cat", "cat"]


# ---- rhyme ----


def test_slice_grid_line_text_uses_word_tokens():
    line = syllabify_text("I _ go _ home")["lines"][0]
    sliced = slice_grid_line(line, 1, None)
    assert sliced.raw_text == "go home"
    assert [c.syllable for c in sliced.cells if not c.rest] == ["go", "home"]
    ranged = slice_grid_line(line, 0, 2)
    assert ranged.raw_text == "I go"


def test_legacy_snippet_rhymes_read_display_text():
    results = find_rhymes_in_texts("cat", {"s1": "hat_bat _"})
    assert {r["word"] for r in results} == {"hat", "bat"}
    assert all(r["snippet_preview"] == "hat bat" for r in results)


async def test_text_anchor_ignores_rest_marks():
    anchor = TextAnchor(kind="text", text="go_now _")
    resolved = await resolve_anchor(anchor, "u1", None, DEFAULT_DIALECT_MAP)  # type: ignore[arg-type]
    assert resolved.unit.text == "go now"


# ---- merge ----


def test_merge_word_count_ignores_rests():
    assert word_count("a _ b") == 2
    assert word_count("go_now\n_ _") == 2


def test_horizontal_merge_rebases_word_indexes_by_words_only():
    left_line = {
        "cells": [{"syllable": "i", "stress": 1, "word_index": 0}, REST, {"syllable": "go", "stress": 1, "word_index": 1}],
        "syllable_count": 2,
        "raw_text": "I _ go",
    }
    left = source("a", "I _ go", grid_data=grid(left_line), x=0)
    right = source("b", "now", x=10)
    merged = merge_snippets([left, right], MergeMode.horizontal)
    assert merged.grid.lines[0].raw_text == "I _ go now"
    assert [c.word_index for c in merged.grid.lines[0].cells] == [0, None, 1, 2]


# ---- export ----


def _snippet(text: str, kind: SnippetType = SnippetType.verse):
    return SimpleNamespace(snippet_type=kind, raw_text=text, tags=[])


def test_text_export_uses_display_text():
    out = export_as_text([_snippet("I _ go\n_ _\n\nhome_now")])
    assert out == "[Verse]\nI go\n\nhome now\n"


def test_markdown_export_uses_display_text():
    out = export_as_markdown([_snippet("a _ b")])
    assert "a b" in out
    assert "_" not in out


def test_pdf_export_draws_display_text(monkeypatch):
    drawn: list[str] = []

    from reportlab.pdfgen import canvas as pdf_canvas

    original = pdf_canvas.Canvas.drawString

    def spy(self, x, y, text, *args, **kwargs):
        drawn.append(text)
        return original(self, x, y, text, *args, **kwargs)

    monkeypatch.setattr(pdf_canvas.Canvas, "drawString", spy)
    export_as_pdf([_snippet("I _ go\n_\nhome")])
    assert "I go" in drawn
    assert "home" in drawn
    assert not any("_" in text for text in drawn)
