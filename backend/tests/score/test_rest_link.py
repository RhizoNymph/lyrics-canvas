"""apply_rest_link (spec section 8)."""

from __future__ import annotations

import copy

import pytest

from app.services.score import ScoreInvalid, apply_rest_link, check_score
from tests.score.helpers import grid, line, score, texts_of


def link(raw_score, g):
    return apply_rest_link(check_score(raw_score, g), g)


def test_spec_example():
    g = grid("I go now")
    out = link(score([[(0, 0, 24, 0), (1, 72, 24, 0), (2, 96, 24, 0)]]), g)
    assert texts_of(out) == ["I _ go now"]
    assert out["lines"][0]["cells"][1] == {"rest": True}
    assert out["lines"][0]["syllable_count"] == 3


def test_gap_just_under_a_beat_makes_no_rest():
    g = grid("I go")
    assert link(score([[(0, 0, 24, 0), (1, 71, 24, 0)]]), g) == g


def test_removes_extra_rests_and_keeps_the_first_cells():
    g = grid("I _ _ _ go")
    g["lines"][0]["cells"][1]["mark"] = "keep me"
    out = link(score([[(0, 0, 24, 0), (1, 72, 24, 0)]]), g)
    assert texts_of(out) == ["I _ go"]
    assert out["lines"][0]["cells"][1] == {"rest": True, "mark": "keep me"}


def test_unplaced_neighbours_and_edges_are_untouched():
    g = grid("_ a _ _ b c _ d _")
    # a ends 12, b at 200: floor(188 / 48) = 3 rests; b..c (c unplaced) and c..d keep theirs
    out = link(score([[(0, 0, 12, 0), (1, 200, 12, 0), (3, 400, 12, 0)]]), g)
    cells = out["lines"][0]["cells"]
    assert texts_of(out) == ["_ a _ _ _ b c _ d _"]
    assert cells[0] == {"rest": True}


def test_unchanged_lines_keep_their_text_byte_for_byte():
    g = grid("I go", "you  stay")
    g["lines"][1]["raw_text"] = "  You, stay!  "
    out = link(score([[(0, 0, 12, 0), (1, 12, 12, 0)], []]), g)
    assert out["lines"][1] is g["lines"][1]
    assert out["lines"][0] is g["lines"][0]


def test_rest_inside_a_word_lands_after_the_word_in_the_text():
    g = grid("hel-lo world")
    out = link(score([[(0, 0, 12, 0), (1, 60, 12, 0), (2, 72, 12, 0)]]), g)
    cells = out["lines"][0]["cells"]
    assert [c.get("syllable", "_") for c in cells] == ["hel", "_", "lo", "world"]
    assert texts_of(out) == ["hello _ world"]


def test_beat_unit_is_taken_at_the_end_of_the_left_note():
    # 4/4 bar 0, 6/8 from bar 1 (tick 192). Left note ends at 192 -> beat unit 24.
    g = grid("a b")
    out = link(score([[(0, 168, 24, 0), (1, 264, 12, 0)]], meters=[(0, 4, 4), (1, 6, 8)]), g)
    assert texts_of(out) == ["a _ _ _ b"]
    # Ending at 191 -> still 4/4 -> beat unit 48: floor(73/48) = 1
    out = link(score([[(0, 167, 24, 0), (1, 264, 12, 0)]], meters=[(0, 4, 4), (1, 6, 8)]), g)
    assert texts_of(out) == ["a _ b"]


def test_null_grid_and_empty_lines():
    assert apply_rest_link(check_score(score([]), None), None) is None
    g = {"lines": [line("_ _"), {"cells": [], "syllable_count": 0, "raw_text": ""}]}
    assert link(score([[], []]), g) == g


def test_other_keys_of_grid_and_line_are_kept():
    g = grid("I go")
    g["version"] = 3
    g["lines"][0]["arranged"] = True
    out = link(score([[(0, 0, 12, 0), (1, 60, 12, 0)]]), g)
    assert out["version"] == 3 and out["lines"][0]["arranged"] is True


def test_idempotent():
    g = grid("a _ b-c _ _ d e")
    s = check_score(score([[(0, 0, 12, 0), (1, 100, 12, 0), (3, 300, 12, 0), (4, 400, 12, 0)]]), g)
    once = apply_rest_link(s, g)
    assert apply_rest_link(check_score(score([[(0, 0, 12, 0), (1, 100, 12, 0), (3, 300, 12, 0), (4, 400, 12, 0)]]), once), once) == once


def test_input_grid_is_not_mutated():
    g = grid("I go")
    before = copy.deepcopy(g)
    link(score([[(0, 0, 12, 0), (1, 200, 12, 0)]]), g)
    assert g == before


def test_invalid_score_for_grid_raises():
    s = check_score(score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), grid("I go"))
    with pytest.raises(ScoreInvalid):
        apply_rest_link(s, grid("I"))
