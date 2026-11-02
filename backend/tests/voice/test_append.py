"""Appending voice-take lines to an existing snippet: existing grid kept verbatim."""

import copy

import pytest

from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics_service import compute_snippet_phonetics, split_lines
from app.services.voice.append import AppendError, append_lines

REST = {"rest": True}

# Hand-arranged: rests inserted, "dog" stress changed by hand, an extra client key on a cell and a line.
ARRANGED = {
    "lines": [
        {"cells": [{"syllable": "cat", "stress": 1, "word_index": 0}, REST, REST], "syllable_count": 1, "raw_text": "cat", "arranged": True},
        {
            "cells": [{"syllable": "dog", "stress": 0, "word_index": 0, "note": "x"}, REST, {"syllable": "bat", "stress": 1, "word_index": 1}],
            "syllable_count": 2,
            "raw_text": "dog bat",
        },
    ]
}


def test_existing_grid_lines_are_kept_verbatim_and_new_lines_syllabified():
    before = copy.deepcopy(ARRANGED)
    result = append_lines("cat\ndog bat", ARRANGED, ["sleeping on the mat"])
    assert result.raw_text == "cat\ndog bat\nsleeping on the mat"
    assert result.grid_data["lines"][:2] == before["lines"]
    assert result.grid_data["lines"][2:] == syllabify_text("sleeping on the mat")["lines"]
    assert ARRANGED == before  # inputs untouched


def test_several_new_lines_in_order():
    result = append_lines("cat", {"lines": ARRANGED["lines"][:1]}, ["one two", "three"])
    assert result.raw_text == "cat\none two\nthree"
    assert [line["raw_text"] for line in result.grid_data["lines"]] == ["cat", "one two", "three"]


@pytest.mark.parametrize("text", ["cat\ndog bat\n", "cat\ndog bat\n\n", "cat\ndog bat\n  \n\t", "cat\ndog bat\n\n\n"])
def test_blank_trailing_text_lines_are_reused(text):
    result = append_lines(text, ARRANGED, ["new"])
    assert result.raw_text == "cat\ndog bat\nnew"


@pytest.mark.parametrize(
    "blank",
    [
        {"cells": [], "syllable_count": 0, "raw_text": ""},
        {"cells": [REST, REST], "syllable_count": 0, "raw_text": "  "},
        {"cells": []},
    ],
)
def test_blank_trailing_grid_lines_are_reused(blank):
    grid = {"lines": [*ARRANGED["lines"], blank]}
    result = append_lines("cat\ndog bat\n", grid, ["new"])
    assert [line["raw_text"] for line in result.grid_data["lines"]] == ["cat", "dog bat", "new"]


def test_a_trailing_line_with_text_is_not_dropped():
    grid = {"lines": [*ARRANGED["lines"], {"cells": [], "syllable_count": 0, "raw_text": "..."}]}
    result = append_lines("cat\ndog bat\n...", grid, ["new"])
    assert [line["raw_text"] for line in result.grid_data["lines"]] == ["cat", "dog bat", "...", "new"]
    assert result.raw_text == "cat\ndog bat\n...\nnew"


def test_blank_lines_in_the_middle_of_the_text_are_kept():
    result = append_lines("cat\n\ndog bat", ARRANGED, ["new"])
    assert result.raw_text == "cat\n\ndog bat\nnew"


def test_trailing_spaces_on_the_last_real_line_are_kept():
    assert append_lines("cat  ", {"lines": ARRANGED["lines"][:1]}, ["new"]).raw_text == "cat  \nnew"


@pytest.mark.parametrize("text, grid", [("", {"lines": []}), ("", None), ("\n \n", {"lines": [{"cells": []}]})])
def test_empty_snippet_gets_just_the_new_lines(text, grid):
    result = append_lines(text, grid, ["one", "two"])
    assert result.raw_text == "one\ntwo"
    assert result.grid_data == syllabify_text("one\ntwo")


def test_without_a_grid_the_existing_text_is_syllabified():
    result = append_lines("cat\ndog bat", None, ["new"])
    assert result.grid_data == syllabify_text("cat\ndog bat\nnew")


def test_grid_without_lines_key_is_treated_as_empty():
    result = append_lines("", {}, ["new"])
    assert result.grid_data == syllabify_text("new")


def test_new_lines_are_trimmed_and_must_not_be_blank():
    assert append_lines("", None, ["  new  "]).raw_text == "new"
    with pytest.raises(AppendError):
        append_lines("cat", None, [])
    with pytest.raises(AppendError):
        append_lines("cat", None, ["ok", "  "])


def test_text_and_grid_lines_stay_aligned_for_phonetics():
    result = append_lines("cat\ndog bat\n", ARRANGED, ["sleeping on the mat", "hat"])
    assert len(split_lines(result.raw_text)) == len(result.grid_data["lines"])
    phonetics = compute_snippet_phonetics(result.raw_text, result.grid_data, None)
    assert [line.line_index for line in phonetics.lines] == [0, 1, 2, 3]
    assert phonetics.lines[3].rhyme_key == ["AE", "T"]
    # the hand-set stress of "dog" survives as an override
    assert "1:0" in phonetics.overrides
