"""Span enumeration: which word ranges of a line take part in pattern detection."""

from app.services.phonetics import DEFAULT_DIALECT_MAP
from app.services.phonetics.pattern.spans import build_span, is_function_word, line_spans, stressed_tail

from .helpers import line_words


def _texts(line: str, max_span_words: int = 2) -> set[str]:
    (words,) = line_words([line])
    return {s.unit.text for s in line_spans("s", words, DEFAULT_DIALECT_MAP, max_span_words)}


def test_content_words_and_two_word_spans():
    spans = _texts("steady weather")
    assert spans == {"steady", "weather", "steady weather"}


def test_max_span_words_bounds_the_span_length():
    assert "keep steady weather" not in _texts("keep steady weather")
    assert "keep steady weather" in _texts("keep steady weather", max_span_words=3)
    assert _texts("keep steady weather", max_span_words=1) == {"keep", "steady", "weather"}


def test_function_words_never_start_or_form_a_span_alone():
    spans = _texts("the weather of my heart")
    for bad in ("the", "the weather", "of", "of my", "my", "my heart"):
        assert bad not in spans
    assert {"weather", "heart"} <= spans


def test_function_words_never_end_a_span_inside_the_line():
    spans = _texts("I keep it steady")
    assert "keep it" not in spans
    assert "it" not in spans and "i" not in spans
    assert {"keep", "steady"} <= spans


def test_a_stressed_function_word_ending_the_line_is_a_span():
    spans = _texts("when the pressure on")
    assert "on" in spans and "pressure on" in spans
    # unstressed function words stay out even at the end
    assert "the" not in _texts("pressure of the")


def test_tail_and_k_from_the_first_stressed_syllable():
    (words,) = line_words(["already the pressure on"])
    spans = {s.unit.text: s for s in line_spans("s", words, DEFAULT_DIALECT_MAP, 2)}
    assert spans["pressure"].tail == 2 and spans["pressure"].k == 2 and spans["pressure"].tail_stress == "10"
    assert spans["pressure on"].tail == 3 and spans["pressure on"].k == 3
    assert spans["on"].tail == 1


def test_stressed_tail_counts_from_the_first_stress_and_defaults_to_one():
    (words,) = line_words(["the"])
    assert stressed_tail(words.words[0].syllables) == 1
    (words,) = line_words(["tomorrow"])
    assert stressed_tail(words.words[0].syllables) == 2  # to-MOR-row


def test_is_function_word():
    (words,) = line_words(["the on my steady and"])
    flags = [is_function_word(w) for w in words.words]
    assert flags == [True, True, True, False, True]


def test_build_span_takes_any_range_with_a_syllable():
    (words,) = line_words(["the pressure on"])
    span = build_span("s1", words, 0, 1, DEFAULT_DIALECT_MAP)
    assert span is not None and span.unit.text == "the"
    assert span.unit.id == "note:s1:0:0-1"
    assert span.pos.word_start == 0 and span.pos.word_end == 1
    (digits,) = line_words(["42 steady"])
    assert build_span("s1", digits, 0, 1, DEFAULT_DIALECT_MAP) is None
