"""Pure merge rules; cases mirror the web client's former lib/merge tests."""

import pytest

from app.models.snippet import GridMode, SnippetType
from app.schemas.merge import MergeMode
from app.services.merge_service import (
    TooFewSnippetsError,
    count_syllables,
    fit_to_mode,
    lines_of,
    merge_horizontal,
    merge_snippets,
    merge_vertical,
    order_by_position,
    text_lines,
    union_tag_ids,
    word_count,
)
from app.schemas.snippet import GridLineModel
from tests.merge_fixtures import dumped, dumped_cells, grid, line, source

REST = {"rest": True}


def lines_from(*specs: dict) -> list[GridLineModel]:
    return [GridLineModel.model_validate(spec) for spec in specs]


# ---- grid helpers ----


def test_text_lines_and_word_count_split_like_the_syllabifier():
    assert text_lines("  one two \n\n three ") == ["one two", "three"]
    assert word_count("  one two \n\n three ") == 3
    assert word_count("") == 0


def test_lines_of_returns_the_grid_lines():
    s = source("a", "one two\nthree")
    assert lines_of(s) == list(s.grid.lines)


def test_lines_of_falls_back_to_cell_less_text_lines_without_a_grid():
    s = source("a", "one two\n\nthree", grid_data=None)
    assert dumped(lines_of(s)) == [
        {"cells": [], "syllable_count": 0, "raw_text": "one two"},
        {"cells": [], "syllable_count": 0, "raw_text": "three"},
    ]


def test_count_syllables_ignores_rests():
    assert count_syllables(lines_from(line("a b", "a . b ."))[0].cells) == 2


def test_fit_to_mode_auto_leaves_ragged_lines_alone():
    lines = lines_from(line("a b"), line("c"))
    assert fit_to_mode(lines, GridMode.auto, None) == (lines, None)


def test_fit_to_mode_fixed_pads_short_lines_with_rests():
    lines, beats = fit_to_mode(lines_from(line("a b"), line("c")), GridMode.fixed, 4)
    assert beats == 4
    assert [len(ln.cells) for ln in lines] == [4, 4]
    assert dumped_cells(lines[1].cells[1:]) == [REST, REST, REST]
    assert lines[1].syllable_count == 1


def test_fit_to_mode_fixed_raises_beats_instead_of_truncating():
    lines, beats = fit_to_mode(lines_from(line("a b c d e"), line("f")), GridMode.fixed, 4)
    assert beats == 5
    assert [len(ln.cells) for ln in lines] == [5, 5]
    assert [c.syllable for c in lines[0].cells] == ["a", "b", "c", "d", "e"]


def test_fit_to_mode_fixed_without_beats_uses_the_longest_line():
    lines, beats = fit_to_mode(lines_from(line("a b"), line("c")), GridMode.fixed, None)
    assert beats == 2
    assert dumped_cells(lines[1].cells) == [{"syllable": "c", "stress": 1, "word_index": 0}, REST]


def test_fit_to_mode_fixed_with_no_cells_keeps_beats():
    lines = lines_from({"cells": [], "syllable_count": 0, "raw_text": "x"})
    assert fit_to_mode(lines, GridMode.fixed, None) == (lines, None)


def test_union_tag_ids_first_seen_order_without_duplicates():
    a = source("a", "a", tags=("t2", "t1"))
    b = source("b", "b", tags=("t1", "t3"))
    assert union_tag_ids([a, b]) == ("t2", "t1", "t3")


# ---- order ----

A = source("a", "a", x=100, y=300)
B = source("b", "b", x=300, y=100)
C = source("c", "c", x=200, y=100)
D = source("d", "d", x=100, y=100)


def test_order_vertical_by_y_then_x():
    assert [s.id for s in order_by_position([A, B, C, D], MergeMode.vertical)] == ["d", "c", "b", "a"]


def test_order_horizontal_by_x_then_y():
    assert [s.id for s in order_by_position([A, B, C, D], MergeMode.horizontal)] == ["d", "a", "c", "b"]


def test_order_breaks_exact_ties_by_id_and_does_not_mutate_input():
    e = source("e", "e", x=100, y=100)
    given = [e, D]
    assert [s.id for s in order_by_position(given, MergeMode.vertical)] == ["d", "e"]
    assert [s.id for s in given] == ["e", "d"]


# ---- vertical ----


def test_vertical_stacks_lines_and_joins_text_with_newlines():
    a = source("a", "one two\nthree", x=10, y=20)
    b = source("b", "four", x=500, y=600)
    merged = merge_vertical([a, b])
    assert merged.raw_text == "one two\nthree\nfour"
    assert [ln.raw_text for ln in merged.grid.lines] == ["one two", "three", "four"]
    assert [ln.syllable_count for ln in merged.grid.lines] == [2, 1, 1]
    assert dumped_cells(merged.grid.lines[0].cells) == dumped_cells(a.grid.lines[0].cells)
    assert merged.source_ids == ("a", "b")


def test_vertical_takes_type_mode_position_group_from_first():
    a = source("a", "one", snippet_type=SnippetType.chorus, x=10, y=20, group_id="g1")
    b = source("b", "two", snippet_type=SnippetType.verse, x=1, y=2, group_id="g2")
    merged = merge_vertical([a, b])
    assert (merged.snippet_type, merged.grid_mode, merged.beats_per_line) == (SnippetType.chorus, GridMode.auto, None)
    assert (merged.position_x, merged.position_y, merged.group_id) == (10, 20, "g1")


def test_vertical_auto_keeps_ragged_counts():
    merged = merge_vertical([source("a", "one two three"), source("b", "four")])
    assert [len(ln.cells) for ln in merged.grid.lines] == [3, 1]
    assert merged.beats_per_line is None


def test_vertical_fixed_pads_to_beats_per_line():
    a = source("a", "one two", grid_mode=GridMode.fixed, beats_per_line=4)
    b = source("b", "three", grid_mode=GridMode.fixed, beats_per_line=2)
    merged = merge_vertical([a, b])
    assert merged.grid_mode == GridMode.fixed
    assert merged.beats_per_line == 4
    assert [len(ln.cells) for ln in merged.grid.lines] == [4, 4]
    assert dumped_cells(merged.grid.lines[1].cells[1:]) == [REST, REST, REST]
    assert [ln.syllable_count for ln in merged.grid.lines] == [2, 1]


def test_vertical_fixed_extends_beats_rather_than_truncating():
    a = source("a", "one", grid_mode=GridMode.fixed, beats_per_line=2)
    b = source("b", "two three four five five")
    merged = merge_vertical([a, b])
    assert merged.beats_per_line == 5
    assert [len(ln.cells) for ln in merged.grid.lines] == [5, 5]
    assert [c.syllable for c in merged.grid.lines[1].cells] == ["two", "three", "four", "five", "five"]


def test_vertical_keeps_rests_and_stress():
    a = source("a", "one two", grid_data=grid(line("one two", "one . two")))
    merged = merge_vertical([a, source("b", "three")])
    assert dumped_cells(merged.grid.lines[0].cells) == [
        {"syllable": "one", "stress": 1, "word_index": 0},
        REST,
        {"syllable": "two", "stress": 1, "word_index": 1},
    ]


def test_vertical_unions_tags_in_order():
    a = source("a", "one", tags=("t1",))
    b = source("b", "two", tags=("t2", "t1"))
    assert merge_vertical([a, b]).tag_ids == ("t1", "t2")


def test_vertical_null_grid_contributes_cell_less_text_lines():
    merged = merge_vertical([source("a", "one"), source("b", "two three", grid_data=None)])
    assert merged.raw_text == "one\ntwo three"
    assert merged.grid.lines[1].model_dump(mode="json") == {"cells": [], "syllable_count": 0, "raw_text": "two three"}


def test_vertical_does_not_share_cells_with_the_sources():
    a = source("a", "one")
    merged = merge_vertical([a, source("b", "two")])
    assert merged.grid.lines[0].cells[0] is not a.grid.lines[0].cells[0]
    merged.grid.lines[0].cells[0].syllable = "changed"
    assert a.grid.lines[0].cells[0].syllable == "one"


def test_vertical_keeps_cell_extras():
    a = source("a", "one", grid_data=grid({"cells": [{"syllable": "one", "stress": 1, "word_index": 0, "word": "one,"}], "syllable_count": 1, "raw_text": "one,"}))
    merged = merge_vertical([a, source("b", "two")])
    assert merged.grid.lines[0].cells[0].model_dump(mode="json")["word"] == "one,"


# ---- horizontal ----


def test_horizontal_joins_line_i_and_concatenates_cells():
    merged = merge_horizontal([source("a", "one two\nthree"), source("b", "four\nfive six")])
    assert merged.raw_text == "one two four\nthree five six"
    assert [ln.raw_text for ln in merged.grid.lines] == ["one two four", "three five six"]
    assert [[c.syllable for c in ln.cells] for ln in merged.grid.lines] == [["one", "two", "four"], ["three", "five", "six"]]
    assert [ln.syllable_count for ln in merged.grid.lines] == [3, 3]


def test_horizontal_rebases_word_index():
    merged = merge_horizontal([source("a", "one two"), source("b", "three"), source("c", "four five")])
    assert [c.word_index for c in merged.grid.lines[0].cells] == [0, 1, 2, 3, 4]


def test_horizontal_rebases_by_text_word_count_not_cells():
    a = source("a", "one two", grid_data=grid(line("one two", "one")))
    merged = merge_horizontal([a, source("b", "three")])
    assert [c.word_index for c in merged.grid.lines[0].cells] == [0, 2]


def test_horizontal_missing_middle_line_contributes_nothing():
    merged = merge_horizontal([source("a", "a1\na2\na3"), source("b", "b1"), source("c", "c1\nc2")])
    assert merged.raw_text == "a1 b1 c1\na2 c2\na3"
    assert dumped_cells(merged.grid.lines[1].cells) == [
        {"syllable": "a2", "stress": 1, "word_index": 0},
        {"syllable": "c2", "stress": 1, "word_index": 1},
    ]


def test_horizontal_keeps_rests_in_place():
    a = source("a", "one", grid_data=grid(line("one", "one .")))
    b = source("b", "two", grid_data=grid(line("two", ". two")))
    merged = merge_horizontal([a, b])
    assert dumped_cells(merged.grid.lines[0].cells) == [
        {"syllable": "one", "stress": 1, "word_index": 0},
        REST,
        REST,
        {"syllable": "two", "stress": 1, "word_index": 1},
    ]
    assert merged.grid.lines[0].syllable_count == 2


def test_horizontal_takes_first_block_fields():
    a = source("a", "one", snippet_type=SnippetType.hook, x=10, y=20, group_id="g1")
    b = source("b", "two", snippet_type=SnippetType.verse, x=1, y=2, group_id="g2")
    merged = merge_horizontal([a, b])
    assert (merged.snippet_type, merged.grid_mode, merged.beats_per_line) == (SnippetType.hook, GridMode.auto, None)
    assert (merged.position_x, merged.position_y, merged.group_id, merged.source_ids) == (10, 20, "g1", ("a", "b"))


def test_horizontal_fixed_pads_to_the_longest_joined_line():
    a = source("a", "one\ntwo three", grid_mode=GridMode.fixed, beats_per_line=2)
    merged = merge_horizontal([a, source("b", "four five")])
    assert merged.beats_per_line == 3
    assert [len(ln.cells) for ln in merged.grid.lines] == [3, 3]
    assert merged.grid.lines[1].cells[2].model_dump(mode="json") == REST


def test_horizontal_unions_tags_and_uses_text_lines_for_null_grid():
    a = source("a", "one", tags=("t1",))
    b = source("b", "two", grid_data=None, tags=("t2",))
    merged = merge_horizontal([a, b])
    assert merged.tag_ids == ("t1", "t2")
    assert merged.raw_text == "one two"
    assert [c.syllable for c in merged.grid.lines[0].cells] == ["one"]


# ---- merge_snippets ----


def test_merge_snippets_orders_by_position_for_the_mode():
    top = source("top", "top", x=500, y=0)
    left = source("left", "left", x=0, y=100)
    assert merge_snippets([left, top], MergeMode.vertical).raw_text == "top\nleft"
    assert merge_snippets([top, left], MergeMode.horizontal).raw_text == "left top"


@pytest.mark.parametrize("ids", [[], ["a"], ["a", "a"]])
def test_merge_snippets_needs_two_distinct_sources(ids):
    with pytest.raises(TooFewSnippetsError):
        merge_snippets([source(i, i) for i in ids], MergeMode.vertical)


def test_merge_snippets_counts_a_repeated_id_once():
    merged = merge_snippets([source("a", "one"), source("b", "two", y=10), source("a", "one")], MergeMode.vertical)
    assert merged.source_ids == ("a", "b")
    assert merged.raw_text == "one\ntwo"


def test_merged_grid_serializes_without_unset_cell_fields():
    a = source("a", "one", grid_mode=GridMode.fixed, beats_per_line=2)
    merged = merge_snippets([a, source("b", "two", y=10)], MergeMode.vertical)
    assert merged.grid.model_dump(mode="json") == {
        "lines": [
            {"cells": [{"syllable": "one", "stress": 1, "word_index": 0}, REST], "syllable_count": 1, "raw_text": "one"},
            {"cells": [{"syllable": "two", "stress": 1, "word_index": 0}, REST], "syllable_count": 1, "raw_text": "two"},
        ]
    }
