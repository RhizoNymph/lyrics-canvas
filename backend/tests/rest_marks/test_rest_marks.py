"""Rest-mark helpers beyond the shared vectors: word tokens, display lines, rebuild edge cases."""

from __future__ import annotations

from app.services.rest_marks import (
    REST_MARK,
    Rest,
    Word,
    display_lines,
    display_text,
    has_rest,
    rebuild_line_text,
    tokenize_line,
    word_tokens,
)


def test_rest_mark_is_underscore():
    assert REST_MARK == "_"


def test_word_tokens_drop_rests_and_keep_punctuation():
    assert word_tokens("don't_stop, _ now!") == ("don't", "stop,", "now!")
    assert word_tokens("_ _") == ()
    assert word_tokens("") == ()


def test_tokens_are_typed():
    assert tokenize_line("a _") == (Word("a"), Rest())


def test_unicode_whitespace_separates_tokens():
    assert word_tokens("a b _c") == ("a", "b", "c")


def test_has_rest():
    assert has_rest("a _ b")
    assert has_rest("a_b")
    assert not has_rest("a b")
    assert not has_rest("")


def test_display_lines_keep_stanza_breaks_and_drop_rest_only_lines():
    assert display_lines("a _\n_\n\nb") == ["a", "", "b"]
    assert display_lines("") == [""]


def test_display_text_of_carriage_returns_is_whitespace():
    # \r is whitespace inside a line; lines split on \n only (as the syllabifier does)
    assert display_text("a _\r\nb") == "a\nb"


def test_display_text_is_idempotent():
    for text in ["I _ go", "a _\n_\nb", "verse one\n\nverse _ two", "  x  "]:
        once = display_text(text)
        assert display_text(once) == once


def test_rebuild_anonymous_cells_emit_each_syllable():
    cells = [{"syllable": "la"}, {"rest": True}, {"syllable": "la"}]
    assert rebuild_line_text(cells) == "la _ la"


def test_rebuild_uses_word_spelling_once_per_word_index():
    cells = [
        {"syllable": "to", "word_index": 0, "word": "tomorrow,"},
        {"syllable": "mor", "word_index": 0, "word": "tomorrow,"},
        {"rest": True},
        {"syllable": "row", "word_index": 0, "word": "tomorrow,"},
    ]
    assert rebuild_line_text(cells) == "tomorrow, _"


def test_rebuild_concatenates_every_syllable_of_a_word_without_spelling():
    cells = [{"syllable": "hul", "word_index": 3}, {"rest": True}, {"syllable": "la", "word_index": 3}]
    assert rebuild_line_text(cells) == "hulla _"
