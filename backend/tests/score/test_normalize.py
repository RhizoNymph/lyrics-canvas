"""normalize_word, word_key, line_key and lcs_pairs (spec section 10)."""

from __future__ import annotations

import pytest

from app.services.score import lcs_pairs, line_key, normalize_word, word_key
from tests.score.helpers import line


@pytest.mark.parametrize(
    "text, expected",
    [
        ("Don't,", "don't"),
        ("HELLO!", "hello"),
        ("Café", "café"),
        ("’til", "'til"),
        ("—", ""),
        ("4ever", "4ever"),
        ("ΣΑΣ", "σασ"),
        ("rock-n-roll", "rocknroll"),
        ("x²", "x"),
        ("日本", "日本"),
        ("", ""),
        ("İ", "i"),
    ],
)
def test_normalize_word(text, expected):
    assert normalize_word(text) == expected


def test_word_key_fallback():
    assert word_key("Hello") == "hello"
    assert word_key("...") == "#..."
    assert word_key("") == "#"


def test_line_key():
    assert line_key(line("Hel-lo, World!")) == "hello world"
    assert line_key(line("_ _")) == ""
    assert line_key({"cells": [{"syllable": "la"}, {"syllable": "LA"}]}) == "la la"


@pytest.mark.parametrize(
    "a, b, pairs",
    [
        ([], [], []),
        (["a"], [], []),
        (["la", "la"], ["la", "la", "la"], [(0, 0), (1, 1)]),
        (["la", "la", "la"], ["la", "la"], [(0, 0), (1, 1)]),
        (["x", "y"], ["y", "x"], [(1, 0)]),
        (["a", "b", "c"], ["a", "x", "c"], [(0, 0), (2, 2)]),
        (["a", "b", "c"], ["c", "b", "a"], [(2, 0)]),
        (["", "a"], ["", "a"], [(1, 1)]),
        (["a", "b", "a"], ["b", "a", "b"], [(1, 0), (2, 1)]),
        (["new", "a", "b"], ["a", "b"], [(1, 0), (2, 1)]),
        (["a", "b"], ["new", "a", "b"], [(0, 1), (1, 2)]),
    ],
)
def test_lcs_pairs(a, b, pairs):
    assert lcs_pairs(a, b) == pairs
