import pytest

from app.schemas.phonetics import PronunciationOverride, SnippetPhonetics, SyllableModel
from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics import DEFAULT_DIALECT_MAP, pronounce
from app.services.phonetics.dialect import DialectMap
from app.services.phonetics_service import (
    Disagreement,
    compute_snippet_phonetics,
    compute_snippet_phonetics_with_report,
    note_units_from_phonetics,
    pronunciation_from_line,
    reconcile,
)


def _stresses(line: dict, word_index: int) -> list[int]:
    return [c["stress"] for c in line["cells"] if c.get("word_index") == word_index and not c.get("rest")]


def test_fresh_text_has_no_overrides_and_agrees_with_grid():
    text = "Hello world\nI walk the dog tomorrow"
    grid = syllabify_text(text)
    phonetics, disagreements = compute_snippet_phonetics_with_report(text, grid, None)
    assert disagreements == []
    assert phonetics.overrides == {}
    assert phonetics.dialect_digest == DEFAULT_DIALECT_MAP.digest()
    assert [line.line_index for line in phonetics.lines] == [0, 1]
    for line, grid_line in zip(phonetics.lines, grid["lines"]):
        assert line.stress == "".join(str(c["stress"]) for c in grid_line["cells"])
        assert line.syllable_count == grid_line["syllable_count"]
        assert line.rhyme_key
        assert set(line.rhyme_keys) <= {"1", "2", "3", "4"}
    assert phonetics.lines[1].rhyme_key == ["AA", "R", "OW"]


def test_grid_stress_wins_and_creates_override():
    text = "hello world"
    grid = syllabify_text(text)
    # user marks "hel" unstressed and "lo" stressed
    cells = grid["lines"][0]["cells"]
    assert [c["stress"] for c in cells[:2]] == [0, 1]
    cells[0]["stress"] = 1
    cells[1]["stress"] = 0
    phonetics, disagreements = compute_snippet_phonetics_with_report(text, grid, None)
    assert phonetics.lines[0].stress == "101"
    assert "0:0" in phonetics.overrides
    assert [s.stress for s in phonetics.overrides["0:0"].syllables] == [1, 0]
    assert disagreements == [Disagreement(line_index=0, word_index=0, word="hello", grid_stress="10", engine_stress="01")]
    # phonetics words carry the override so anchors built from them keep the user's stress
    assert [s.stress for s in phonetics.lines[0].words[0].syllables] == [1, 0]


def test_rest_cells_are_ignored():
    text = "hello world"
    grid = syllabify_text(text)
    grid["lines"][0]["cells"].insert(1, {"rest": True})
    grid["lines"][0]["cells"].append({"rest": True})
    phonetics, disagreements = compute_snippet_phonetics_with_report(text, grid, None)
    assert disagreements == []
    assert phonetics.lines[0].stress == "011"


def test_syllable_count_mismatch_right_aligns_and_reports():
    text = "hello"
    grid = {"lines": [{"cells": [{"syllable": "hel", "stress": 1, "word_index": 0}], "syllable_count": 1, "raw_text": "hello"}]}
    phonetics, disagreements = compute_snippet_phonetics_with_report(text, grid, None)
    assert phonetics.lines[0].stress == "1"
    assert phonetics.lines[0].syllable_count == 1
    override = phonetics.overrides["0:0"]
    assert len(override.syllables) == 1
    assert override.syllables[0].nucleus == "OW"  # the engine's last syllable is kept
    assert disagreements[0].grid_stress == "1" and disagreements[0].engine_stress == "01"
    longer = {
        "lines": [
            {
                "cells": [
                    {"syllable": "h", "stress": 0, "word_index": 0},
                    {"syllable": "el", "stress": 1, "word_index": 0},
                    {"syllable": "lo", "stress": 0, "word_index": 0},
                ],
                "syllable_count": 3,
                "raw_text": "hello",
            }
        ]
    }
    phonetics2, _ = compute_snippet_phonetics_with_report(text, longer, None)
    assert phonetics2.lines[0].stress == "010"
    assert len(phonetics2.overrides["0:0"].syllables) == 3


def test_existing_override_is_kept_when_grid_agrees_and_replaced_when_not():
    text = "hello world"
    grid = syllabify_text(text)
    grid["lines"][0]["cells"][0]["stress"] = 1
    grid["lines"][0]["cells"][1]["stress"] = 0
    first = compute_snippet_phonetics(text, grid, None)
    assert first.lines[0].stress == "101"
    # same grid again, passing the previous overrides: still one override, no change
    second, disagreements = compute_snippet_phonetics_with_report(text, grid, first.overrides)
    assert second.overrides == first.overrides
    assert disagreements == []
    # user flips the grid back to the engine's stress: override is dropped
    grid["lines"][0]["cells"][0]["stress"] = 0
    grid["lines"][0]["cells"][1]["stress"] = 1
    third, disagreements = compute_snippet_phonetics_with_report(text, grid, first.overrides)
    assert third.overrides == {}
    assert third.lines[0].stress == "011"
    assert disagreements == []


def test_custom_override_phones_are_respected():
    text = "cat"
    grid = syllabify_text(text)
    overrides = {"0:0": PronunciationOverride(syllables=[SyllableModel(onset=["K"], nucleus="AA", coda=["T"], stress=1)])}
    phonetics = compute_snippet_phonetics(text, grid, overrides)
    assert phonetics.lines[0].rhyme_key == ["AA", "T"]
    assert phonetics.overrides == overrides
    assert phonetics.lines[0].words[0].source == "override"


def test_unpronounceable_tokens_keep_word_index_alignment():
    text = "42 cats & dogs"
    grid = syllabify_text(text)
    phonetics, disagreements = compute_snippet_phonetics_with_report(text, grid, None)
    assert disagreements == []
    words = phonetics.lines[0].words
    assert [w.word for w in words] == ["", "cats", "", "dogs"]
    assert words[0].syllables == [] and words[2].syllables == []
    assert phonetics.lines[0].stress == "11"


def test_empty_and_blank_lines_are_skipped_like_the_grid():
    text = "\n\nhello\n\nworld\n"
    grid = syllabify_text(text)
    phonetics = compute_snippet_phonetics(text, grid, None)
    assert len(grid["lines"]) == 2
    assert [line.line_index for line in phonetics.lines] == [0, 1]
    assert compute_snippet_phonetics("", {"lines": []}, None).lines == []


def test_reconcile_is_pure_and_typed():
    pron = pronounce("hello world")
    cells = [
        {"syllable": "hel", "stress": 1, "word_index": 0},
        {"syllable": "lo", "stress": 0, "word_index": 0},
        {"syllable": "world", "stress": 1, "word_index": 1},
    ]
    overrides, disagreements = reconcile(cells, pron)
    assert set(overrides) == {0}
    assert [s.stress for s in overrides[0]] == [1, 0]
    assert disagreements == (Disagreement(0, 0, "hello", "10", "01"),)
    assert reconcile(cells, pron) == (overrides, disagreements)


def test_dialect_changes_stored_keys_and_digest():
    text = "singing"
    plain = DialectMap(rules=())
    p = compute_snippet_phonetics(text, syllabify_text(text), None, dialect=plain)
    assert p.dialect_digest == plain.digest()
    assert p.lines[0].rhyme_keys["1"] == ["IH", "NG"]
    d = compute_snippet_phonetics(text, syllabify_text(text), None)
    assert d.lines[0].rhyme_keys["1"] == ["IH", "N"]


def test_pronunciation_from_line_round_trips():
    text = "I walk the dog"
    phonetics = compute_snippet_phonetics(text, syllabify_text(text), None)
    line = phonetics.lines[0]
    full = pronunciation_from_line(line)
    assert [w.word for w in full.words] == ["i", "walk", "the", "dog"]
    assert full == pronounce(text)
    suffix = pronunciation_from_line(line, word_offset=2)
    assert [w.word for w in suffix.words] == ["the", "dog"]


def test_note_units_from_phonetics_builds_suffix_units():
    text = "I walk the dog\n\nhello world"
    phonetics = compute_snippet_phonetics(text, syllabify_text(text), None)
    units = note_units_from_phonetics("snip-1", phonetics, DEFAULT_DIALECT_MAP, tags=("t1",))
    ids = [u.id for u in units]
    assert ids == [
        "note:snip-1:0:0",
        "note:snip-1:0:1",
        "note:snip-1:0:2",
        "note:snip-1:0:3",
        "note:snip-1:1:0",
        "note:snip-1:1:1",
    ]
    by_id = {u.id: u for u in units}
    assert by_id["note:snip-1:0:2"].text == "the dog"
    assert by_id["note:snip-1:0:2"].word_offset == 2
    assert by_id["note:snip-1:0:2"].line_index == 0
    assert by_id["note:snip-1:0:2"].snippet_id == "snip-1"
    assert by_id["note:snip-1:0:2"].tags == ("t1",)
    assert by_id["note:snip-1:0:2"].rhyme_key == ("AA", "G")
    assert all(u.provenance.value == "note" and not u.ephemeral for u in units)


def test_note_units_apply_overrides():
    text = "hello"
    grid = syllabify_text(text)
    grid["lines"][0]["cells"][0]["stress"] = 1
    grid["lines"][0]["cells"][1]["stress"] = 0
    phonetics = compute_snippet_phonetics(text, grid, None)
    (unit,) = note_units_from_phonetics("s", phonetics, DEFAULT_DIALECT_MAP)
    assert unit.stress == "10"
    assert unit.pronunciation_override is not None


def test_snippet_phonetics_json_round_trip():
    text = "hello world"
    phonetics = compute_snippet_phonetics(text, syllabify_text(text), None)
    data = phonetics.model_dump(mode="json")
    assert SnippetPhonetics.model_validate(data) == phonetics
    assert set(data) == {"dialect_digest", "lines", "overrides"}
    assert set(data["lines"][0]) == {"line_index", "words", "stress", "syllable_count", "rhyme_key", "rhyme_keys"}
    assert set(data["lines"][0]["words"][0]) == {"word", "source", "syllables"}
    assert set(data["lines"][0]["words"][0]["syllables"][0]) == {"onset", "nucleus", "coda", "stress"}


@pytest.mark.parametrize("text", ["hello world", "in the dark", "supercalifragilistic flarbnip", "don't stop\nnever quit"])
def test_new_text_grid_and_phonetics_stress_agree(text: str):
    grid = syllabify_text(text)
    phonetics, disagreements = compute_snippet_phonetics_with_report(text, grid, None)
    assert disagreements == []
    for line, grid_line in zip(phonetics.lines, grid["lines"]):
        assert line.stress == "".join(str(c["stress"]) for c in grid_line["cells"])
