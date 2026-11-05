"""Word text normalization and validation (docs/features/word_bank.md#word-text-rules)."""

from __future__ import annotations

import pytest

from app.services.tag_errors import TagErrorCode
from app.services.word_bank.errors import InvalidWordError
from app.services.word_bank.text import (
    MAX_QUERY_LENGTH,
    MAX_WORD_LENGTH,
    MAX_WORDS,
    WordProblem,
    normalize_word,
    related_query,
    word_problem,
    word_text,
    word_texts,
)


# ---- normalization ----


@pytest.mark.parametrize(
    ("raw", "want"),
    [
        ("night", "night"),
        ("  night  ", "night"),
        ("\tnight\n", "night"),
        ("broken  heart", "broken heart"),
        ("broken \t\n heart", "broken heart"),
        ("a b", "a b"),  # no-break space is whitespace
        ("a　b", "a b"),  # ideographic space
        ("Night", "Night"),  # case kept
        ("NIGHT Light", "NIGHT Light"),
        ("café", "café"),  # NFC composes
        ("", ""),
        ("   ", ""),
    ],
)
def test_normalize_word(raw, want):
    assert normalize_word(raw) == want


def test_normalize_is_idempotent():
    for raw in ["  a  b ", "café", "x y"]:
        once = normalize_word(raw)
        assert normalize_word(once) == once


# ---- valid ----


@pytest.mark.parametrize(
    "raw",
    [
        "night",
        "Night",
        "a",
        "don't",
        "don’t",  # typographic apostrophe
        "'til",
        "rock-n-roll",
        "self-made man",
        "broken heart",
        "one two three four",
        "café",
        "über",
        "ночь",  # Cyrillic
        "夜",  # CJK (category Lo)
        "あい",  # Hiragana
        "x" * MAX_WORD_LENGTH,
        "a-'b",
        "-a-",
    ],
)
def test_valid_words(raw):
    assert word_problem(normalize_word(raw)) is None
    assert word_text(raw) == normalize_word(raw)


def test_word_text_returns_normalized_text():
    assert word_text("  broken   Heart ") == "broken Heart"
    assert word_text("café") == "café"


def test_length_counts_code_points_after_normalizing():
    # 64 composed characters from 128 decomposed code points are fine
    assert word_text("é" * MAX_WORD_LENGTH) == "é" * MAX_WORD_LENGTH
    # surrounding whitespace does not count
    assert word_text("  " + "x" * MAX_WORD_LENGTH + "  ") == "x" * MAX_WORD_LENGTH
    # four words with single spaces fitting 64
    four = " ".join(["x" * 15] * 4)
    assert len(four) == 63
    assert word_text(four.replace(" ", "   ")) == four


# ---- invalid ----


@pytest.mark.parametrize(
    ("raw", "problem"),
    [
        ("", WordProblem.empty),
        ("   ", WordProblem.empty),
        ("\n\t", WordProblem.empty),
        ("x" * (MAX_WORD_LENGTH + 1), WordProblem.too_long),
        ("one two three four five", WordProblem.too_many_words),
        ("a b c d e", WordProblem.too_many_words),
        ("night2", WordProblem.invalid_character),
        ("42", WordProblem.invalid_character),
        ("night!", WordProblem.invalid_character),
        ("night.", WordProblem.invalid_character),
        ("a,b", WordProblem.invalid_character),
        ("a_b", WordProblem.invalid_character),
        ("a/b", WordProblem.invalid_character),
        ("a—b", WordProblem.invalid_character),  # em dash is not a hyphen
        ("\U0001f525", WordProblem.invalid_character),  # emoji
        ("a​b", WordProblem.invalid_character),  # zero-width space (Cf)
        ("a\u0000b", WordProblem.invalid_character),
        ("ẹ̈", WordProblem.invalid_character),  # a mark NFC cannot compose stays Mn
        ("'", WordProblem.no_letter),
        ("-", WordProblem.no_letter),
        ("' - ’", WordProblem.no_letter),
        ("--", WordProblem.no_letter),
    ],
)
def test_invalid_words(raw, problem):
    assert word_problem(normalize_word(raw)) is problem
    with pytest.raises(InvalidWordError) as info:
        word_text(raw)
    assert info.value.problem is problem
    assert info.value.code is TagErrorCode.invalid_word
    assert info.value.status == 422
    assert info.value.index is None
    assert info.value.message


def test_too_long_checked_before_word_count():
    raw = " ".join(["x" * 20] * 5)
    assert word_problem(raw) is WordProblem.too_long


def test_every_problem_has_a_distinct_message():
    messages = {p.message for p in WordProblem}
    assert len(messages) == len(WordProblem)
    assert all(messages)


def test_limits():
    assert (MAX_WORD_LENGTH, MAX_WORDS, MAX_QUERY_LENGTH) == (64, 4, 64)


# ---- batches ----


def test_word_texts_normalizes_every_word_in_order():
    assert word_texts([" a ", "b  c", "D"]) == ["a", "b c", "D"]


def test_word_texts_reports_first_bad_index():
    with pytest.raises(InvalidWordError) as info:
        word_texts(["ok", "fine", "bad1", "worse!"])
    assert info.value.index == 2
    assert info.value.problem is WordProblem.invalid_character
    assert "2" in info.value.message


def test_word_texts_keeps_duplicates():
    # collapsing duplicates is the service's job, not validation's
    assert word_texts(["Night", "night"]) == ["Night", "night"]


# ---- related-word query ----


@pytest.mark.parametrize(
    ("raw", "want"),
    [
        ("night", "night"),
        ("  ocean at night  ", "ocean at night"),
        ("a theme, with punctuation! 123", "a theme, with punctuation! 123"),
        ("x" * MAX_QUERY_LENGTH, "x" * MAX_QUERY_LENGTH),
    ],
)
def test_related_query_valid(raw, want):
    assert related_query(raw) == want


@pytest.mark.parametrize("raw", ["", "   ", "x" * (MAX_QUERY_LENGTH + 1)])
def test_related_query_invalid(raw):
    with pytest.raises(InvalidWordError) as info:
        related_query(raw)
    assert info.value.code is TagErrorCode.invalid_word
    assert info.value.problem in (WordProblem.query_empty, WordProblem.query_too_long)
