"""Recording timings: span invariants, line timings and the jsonb round trip."""

from __future__ import annotations

import pytest

from app.services.voice.line_assembly import TimedWord, assemble_lines
from app.services.voice.timings import (
    LineTiming,
    Timings,
    TimingsError,
    parse_spans,
    parse_words,
    span_problem,
    spans_json,
    stored_timings,
    words_json,
)
from tests.voice.timeline import FakeOracle, timeline

WORDS = (TimedWord("a", 0, 100, 0.9), TimedWord("b", 150, 300, None), TimedWord("c", 400, 500, 0.5))


@pytest.mark.parametrize(
    "spans",
    [((0, 3),), ((0, 1), (1, 3)), ((0, 1), (1, 1), (1, 3)), ((0, 0), (0, 3), (3, 3))],
)
def test_valid_spans(spans):
    assert span_problem(spans, 3) is None
    Timings(WORDS, spans)


@pytest.mark.parametrize(
    "spans",
    [(), ((1, 3),), ((0, 2),), ((0, 2), (1, 3)), ((0, 1), (2, 3)), ((0, 4),), ((0, 2), (2, 1), (1, 3))],
    ids=["none", "late-start", "short", "overlap", "gap", "too-far", "backwards"],
)
def test_invalid_spans_are_unrepresentable(spans):
    assert span_problem(spans, 3) is not None
    with pytest.raises(TimingsError):
        Timings(WORDS, spans)


def test_line_timings_with_a_blank_line():
    timings = Timings(WORDS, ((0, 2), (2, 2), (2, 3)))
    assert timings.line_timings() == (
        LineTiming(0, 2, 0, 300),
        LineTiming(2, 2, None, None),
        LineTiming(2, 3, 400, 500),
    )


def test_of_assembled_lines():
    assembled = assemble_lines(timeline("a b c d"), [750], FakeOracle())
    timings = Timings.of(assembled)
    assert timings.spans == ((0, 2), (2, 4))
    assert len(timings.words) == 4


def test_json_round_trip_keeps_probability():
    raw_words = words_json(WORDS)
    assert raw_words[0] == {"text": "a", "start_ms": 0, "end_ms": 100, "probability": 0.9}
    assert raw_words[1]["probability"] is None
    raw_spans = spans_json(((0, 1), (1, 3)))
    assert raw_spans == [[0, 1], [1, 3]]
    timings = stored_timings(raw_words, raw_spans)
    assert timings is not None
    assert [w.probability for w in timings.words] == [0.9, None, 0.5]
    assert timings.words == WORDS
    assert timings.spans == ((0, 1), (1, 3))


@pytest.mark.parametrize("words, spans", [(None, [[0, 0]]), ([], None), (None, None)])
def test_missing_columns_mean_no_timings(words, spans):
    assert stored_timings(words, spans) is None


@pytest.mark.parametrize("raw", [{"text": "a"}, [{"text": "a"}], [{"text": "a", "start_ms": "x", "end_ms": 1}], "nope"])
def test_malformed_words(raw):
    with pytest.raises(TimingsError):
        parse_words(raw)


@pytest.mark.parametrize("raw", [[[0]], [[0, 1, 2]], "x", [["a", 1]]])
def test_malformed_spans(raw):
    with pytest.raises(TimingsError):
        parse_spans(raw)


def test_stored_spans_that_do_not_tile_are_rejected():
    with pytest.raises(TimingsError):
        stored_timings(words_json(WORDS), [[0, 2]])
