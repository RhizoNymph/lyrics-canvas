from app.services.score_tools.draft.align import align_words, grid_words, take_words
from app.services.score_tools.draft.types import PairKind
from app.services.voice.line_assembly import TimedWord
from tests.score.helpers import grid


def pairs(take: str, grid_text: str) -> list[tuple[int, int, str]]:
    alignment = align_words(take.split(), grid_text.split())
    return [(p.take, p.grid, p.kind.value) for p in alignment.pairs]


def test_identical_sequences_pair_one_to_one():
    assert pairs("a b c", "a b c") == [(0, 0, "exact"), (1, 1, "exact"), (2, 2, "exact")]


def test_missing_take_word_leaves_grid_word_unpaired():
    assert pairs("a c", "a b c") == [(0, 0, "exact"), (1, 2, "exact")]


def test_extra_take_word_is_unmatched():
    alignment = align_words("a x b".split(), "a b".split())
    assert [(p.take, p.grid) for p in alignment.pairs] == [(0, 0), (2, 1)]
    assert alignment.unmatched_take_words == 1


def test_equal_gap_is_filled_by_position():
    assert pairs("the night is yung", "the night is young") == [
        (0, 0, "exact"), (1, 1, "exact"), (2, 2, "exact"), (3, 3, "fill"),
    ]
    assert pairs("i wanna go", "i want to go") == [(0, 0, "exact"), (2, 3, "exact")]
    assert pairs("so r we", "so are we") == [(0, 0, "exact"), (1, 1, "fill"), (2, 2, "exact")]


def test_leading_and_trailing_gaps_fill_when_counts_match():
    assert pairs("wee are", "we are") == [(0, 0, "fill"), (1, 1, "exact")]
    assert pairs("we arr", "we are") == [(0, 0, "exact"), (1, 1, "fill")]


def test_unequal_gap_is_not_filled():
    alignment = align_words("a x y b".split(), "a z b".split())
    assert [(p.take, p.grid, p.kind) for p in alignment.pairs] == [(0, 0, PairKind.exact), (3, 2, PairKind.exact)]
    assert alignment.unmatched_take_words == 2


def test_repeated_words_pair_with_the_latest_occurrence():
    # a take appended after an identical earlier line goes to the later grid words
    assert pairs("hey hey", "hey hey hey hey") == [(0, 2, "exact"), (1, 3, "exact")]


def test_nothing_in_common_and_unequal():
    alignment = align_words(["x"], ["a", "b"])
    assert alignment.pairs == ()
    assert alignment.unmatched_take_words == 1


def test_empty_sides():
    assert align_words([], ["a"]).pairs == ()
    assert align_words(["a"], []).unmatched_take_words == 1


def test_pairs_strictly_increase():
    alignment = align_words("a b x c d e".split(), "a q c d y z".split())
    takes = [p.take for p in alignment.pairs]
    grids = [p.grid for p in alignment.pairs]
    assert takes == sorted(set(takes)) and grids == sorted(set(grids))


def test_grid_words_carry_line_ordinals_syllables_accents_and_keys():
    g = grid("the ^hel-lo _ ^World!", "^go")
    words = grid_words(g)
    assert [(w.line, w.ordinals, w.syllables, w.accents, w.key) for w in words] == [
        (0, (0,), ("the",), (0,), "the"),
        (0, (1, 2), ("hel", "lo"), (1, 0), "hello"),
        (0, (3,), ("World!",), (1,), "world"),
        (1, (0,), ("go",), (1,), "go"),
    ]


def test_grid_words_without_word_field_spell_from_syllables():
    g = {"lines": [{"cells": [
        {"syllable": "beau", "stress": 1, "word_index": 0},
        {"syllable": "ti", "stress": 0, "word_index": 0},
        {"syllable": "ful", "stress": 0, "word_index": 0},
    ]}]}
    assert grid_words(g)[0].key == "beautiful"


def test_grid_words_of_null_grid():
    assert grid_words(None) == []


def test_take_words_normalize_and_make_times_monotonic():
    words = take_words([TimedWord("Don't,", 100, 300), TimedWord("STOP", 90, 80), TimedWord("—", 500, 600)])
    assert [(w.key, w.start_ms, w.end_ms) for w in words] == [
        ("don't", 100.0, 300.0),
        ("stop", 100.0, 100.0),
        ("#—", 500.0, 600.0),
    ]
