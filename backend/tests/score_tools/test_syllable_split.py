import pytest

from app.services.score_tools.draft.syllable_split import split_word, vowel_weight


@pytest.mark.parametrize(
    ("syllable", "weight"),
    [("the", 1), ("beaut", 3), ("rhythm", 1), ("brrr", 1), ("", 1), ("CAFÉ", 2), ("queue", 4), ("my", 1)],
)
def test_vowel_weight(syllable, weight):
    assert vowel_weight(syllable) == weight


def test_single_syllable_takes_the_whole_word():
    assert split_word(100, 400, ["night"]) == [(100, 400)]


def test_split_is_proportional_and_contiguous():
    parts = split_word(0, 500, ["beaut", "if", "ul"])  # weights 3, 1, 1
    assert parts == [(0, 300), (300, 400), (400, 500)]


def test_equal_weights_split_evenly():
    assert split_word(1000, 1300, ["la", "la", "la"]) == [(1000, 1100), (1100, 1200), (1200, 1300)]


def test_zero_length_word_gives_zero_length_parts():
    assert split_word(50, 50, ["a", "b"]) == [(50, 50), (50, 50)]


def test_last_part_ends_exactly_at_the_word_end():
    parts = split_word(0, 1000, ["a", "a", "a"])
    assert parts[-1][1] == 1000
    assert all(parts[i][1] == parts[i + 1][0] for i in range(len(parts) - 1))
