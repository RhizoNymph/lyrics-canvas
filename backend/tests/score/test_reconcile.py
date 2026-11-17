"""reconcile (spec section 11)."""

from __future__ import annotations

import pytest

from app.services.score import ScoreInvalid, check_score, reconcile, score_to_json
from tests.score.helpers import grid, line_from_cells, notes_of, score


def rec(raw, old, new):
    return notes_of(score_to_json(reconcile(check_score(raw, old), old, new)))


ONE = [(0, 0, 12, 1), (1, 12, 12, 0), (2, 48, 24, 2)]


def test_identical_grid_keeps_everything():
    g = grid("hel-lo world")
    assert rec(score([ONE]), g, g) == [ONE]


def test_spec_example_word_inserted():
    assert rec(score([ONE]), grid("hel-lo world"), grid("hel-lo big world")) == [[(0, 0, 12, 1), (1, 12, 12, 0), (3, 48, 24, 2)]]


def test_spec_example_word_changed_is_unplaced():
    assert rec(score([ONE]), grid("hel-lo world"), grid("hel-los world")) == [[(2, 48, 24, 2)]]


def test_word_deleted():
    assert rec(score([ONE]), grid("hel-lo world"), grid("world")) == [[(0, 48, 24, 2)]]


def test_syllable_count_grows_and_shrinks():
    old = grid("beau-ti-ful day")
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0), (2, 24, 12, 0), (3, 48, 12, 1)]])
    assert rec(raw, old, grid("beau-ti-f-ul day")) == [[(0, 0, 12, 0), (1, 12, 12, 0), (2, 24, 12, 0), (4, 48, 12, 1)]]
    assert rec(raw, old, grid("beau-tiful day")) == [[(0, 0, 12, 0), (1, 12, 12, 0), (2, 48, 12, 1)]]


def test_case_and_punctuation_do_not_matter():
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0)]])
    assert rec(raw, grid("I go"), grid("I, GO!")) == [[(0, 0, 12, 0), (1, 12, 12, 0)]]


def test_rests_in_the_new_grid_shift_ordinals_not_notes():
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0)]])
    assert rec(raw, grid("I go"), grid("_ I _ go _")) == [[(0, 0, 12, 0), (1, 12, 12, 0)]]


def test_same_line_count_pairs_by_index():
    old = grid("a b", "c d")
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0)], [(0, 48, 12, 0), (1, 60, 12, 0)]])
    # lines swapped: index pairing, nothing matches except shared words
    assert rec(raw, old, grid("c d", "a b")) == [[], []]
    assert rec(raw, old, grid("a x", "y d")) == [[(0, 0, 12, 0)], [(1, 60, 12, 0)]]


def test_line_inserted_and_deleted():
    old = grid("a b", "c d")
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0)], [(0, 48, 12, 0), (1, 60, 12, 0)]])
    assert rec(raw, old, grid("a b", "new line", "c d")) == [[(0, 0, 12, 0), (1, 12, 12, 0)], [], [(0, 48, 12, 0), (1, 60, 12, 0)]]
    assert rec(raw, old, grid("new", "a b", "c d")) == [[], [(0, 0, 12, 0), (1, 12, 12, 0)], [(0, 48, 12, 0), (1, 60, 12, 0)]]
    assert rec(raw, old, grid("c d")) == [[(0, 48, 12, 0), (1, 60, 12, 0)]]
    assert rec(raw, old, grid()) == []


def test_line_edited_and_another_inserted_loses_the_edited_line():
    old = grid("a b", "c d")
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0)], [(0, 48, 12, 0), (1, 60, 12, 0)]])
    # different counts -> line LCS on whole-line keys: "a x" does not match "a b"
    assert rec(raw, old, grid("a x", "c d", "e")) == [[], [(0, 48, 12, 0), (1, 60, 12, 0)], []]


def test_repeated_lines_pair_from_the_front():
    old = grid("la la", "la la")
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0)], [(0, 48, 12, 0), (1, 60, 12, 0)]])
    out = rec(raw, old, grid("la la", "la la", "la la"))
    assert out == [[(0, 0, 12, 0), (1, 12, 12, 0)], [(0, 48, 12, 0), (1, 60, 12, 0)], []]


def test_interleaved_words_reordered_drop_out_of_order_notes():
    # old cells A0 B0 A1 (word A interleaved with B), new A0 A1 B0
    a0 = {"syllable": "a", "word_index": 0, "word": "ab"}
    a1 = {"syllable": "b", "word_index": 0, "word": "ab"}
    b0 = {"syllable": "c", "word_index": 1, "word": "c"}
    old = {"lines": [line_from_cells([a0, b0, a1])]}
    new = {"lines": [line_from_cells([a0, a1, b0])]}
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0), (2, 24, 12, 0)]])
    # A1 (was s2, t24) becomes s1; B0 (was s1, t12) becomes s2 and starts before A1 ends -> dropped
    assert rec(raw, old, new) == [[(0, 0, 12, 0), (1, 24, 12, 0)]]


def test_punctuation_only_words_match_themselves():
    cells = [{"syllable": "...", "word_index": 0, "word": "..."}, {"syllable": "go", "word_index": 1, "word": "go"}]
    old = {"lines": [line_from_cells(cells)]}
    new = {"lines": [line_from_cells(cells + [{"syllable": "now", "word_index": 2, "word": "now"}])]}
    assert rec(score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), old, new) == [[(0, 0, 12, 0), (1, 12, 12, 0)]]


def test_anonymous_cells_match_by_syllable():
    old = {"lines": [line_from_cells([{"syllable": "la"}, {"syllable": "di"}])]}
    new = {"lines": [line_from_cells([{"syllable": "di"}])]}
    assert rec(score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), old, new) == [[(0, 12, 12, 0)]]


def test_extras_and_settings_are_kept():
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0)]], meters=[(0, 3, 4), (4, 6, 8)], tempos=[(0, 100), (2, 80)], pickup=24, snap="1/32")
    raw["lines"][0]["notes"][1]["p"] = {"midi": 60}
    out = score_to_json(reconcile(check_score(raw, grid("a b")), grid("a b"), grid("x a b")))
    assert out["lines"][0]["notes"] == [{"s": 1, "t": 0, "d": 12, "a": 0}, {"s": 2, "t": 12, "d": 12, "a": 0, "p": {"midi": 60}}]
    assert {k: out[k] for k in ("meters", "tempos", "pickup", "snap")} == {k: raw[k] for k in ("meters", "tempos", "pickup", "snap")}


def test_invalid_old_score_raises():
    raw = score([[(0, 0, 12, 0), (1, 12, 12, 0)]])
    with pytest.raises(ScoreInvalid):
        reconcile(check_score(raw, grid("a b")), grid("a"), grid("a b"))


def test_null_grids():
    s = check_score(score([]), None)
    assert score_to_json(reconcile(s, None, grid("a")))["lines"] == [{"notes": []}]
    raw = score([[(0, 0, 12, 0)]])
    assert score_to_json(reconcile(check_score(raw, grid("a")), grid("a"), None))["lines"] == []
