"""splice_line_texts and changed_line_texts (spec section 13)."""

from __future__ import annotations

import pytest

from app.services.score import ScoreTextMismatch, changed_line_texts, splice_line_texts
from tests.score.helpers import grid


def test_blank_lines_are_kept_and_skipped():
    text = "  first line \n\n   \nsecond\n\nthird"
    assert splice_line_texts(text, {1: "second _"}) == "  first line \n\n   \nsecond _\n\nthird"
    assert splice_line_texts(text, {0: "A", 2: "C"}) == "A\n\n   \nsecond\n\nC"
    assert splice_line_texts(text, {}) == text


def test_missing_line_raises():
    with pytest.raises(ScoreTextMismatch) as info:
        splice_line_texts("a\n\nb", {2: "x"})
    assert (info.value.index, info.value.available) == (2, 2)
    with pytest.raises(ScoreTextMismatch):
        splice_line_texts("", {0: "x"})


def test_changed_line_texts():
    assert changed_line_texts(grid("a b", "c"), grid("a _ b", "c")) == {0: "a _ b"}
    assert changed_line_texts(grid("a"), grid("a")) == {}
    with pytest.raises(ValueError):
        changed_line_texts(grid("a"), grid("a", "b"))
