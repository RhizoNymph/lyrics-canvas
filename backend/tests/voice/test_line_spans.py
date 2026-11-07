"""Word spans behind assembled lines: every split mode exposes them, and they always tile the words.

Property-style: seeded random takes (word counts, timings, blank words, taps) are checked against
the span invariants of docs/features/voice_dataset.md.
"""

from __future__ import annotations

import random

import pytest

from app.models.recording import SplitMode
from app.services.voice.line_assembly import (
    LineAssemblyError,
    TimedWord,
    assemble_lines,
    spans_from_boundaries,
)
from tests.voice.timeline import FakeOracle, numbered, timeline


def check_spans(assembled) -> None:
    """Contiguous, non-overlapping, covering every word in order, and matching the line texts."""
    spans = assembled.spans
    words = assembled.words
    assert len(spans) == len(assembled.lines)
    assert spans[0][0] == 0
    assert spans[-1][1] == len(words)
    for (_, end), (start, _) in zip(spans, spans[1:]):
        assert end == start
    for (start, end), line in zip(spans, assembled.lines):
        assert start < end  # assembled lines are never blank
        assert " ".join(w.text for w in words[start:end]) == line


def test_spans_from_boundaries():
    assert spans_from_boundaries(4, []) == ((0, 4),)
    assert spans_from_boundaries(4, [1]) == ((0, 2), (2, 4))
    assert spans_from_boundaries(5, [0, 2, 3]) == ((0, 1), (1, 3), (3, 4), (4, 5))


def test_single_mode_spans_cover_the_take():
    assembled = assemble_lines(timeline("a b c"), [], FakeOracle())
    assert assembled.split_mode == SplitMode.single
    assert assembled.spans == ((0, 3),)
    check_spans(assembled)


def test_taps_mode_spans():
    assembled = assemble_lines(timeline("a b c d"), [750], FakeOracle())
    assert assembled.split_mode == SplitMode.taps
    assert assembled.lines == ("a b", "c d")
    assert assembled.spans == ((0, 2), (2, 4))
    check_spans(assembled)


def test_auto_mode_spans():
    words = timeline("I saw a black cat sleeping on the mat", gap_ms=80, pauses={4: 500})
    assembled = assemble_lines(words, [], FakeOracle({"sleeping": 2, "saw": 2, "black": 2}))
    assert assembled.split_mode == SplitMode.auto
    assert assembled.spans == ((0, 5), (5, 9))
    check_spans(assembled)


def test_spans_index_the_cleaned_words_when_blank_words_are_dropped():
    words = [TimedWord("a", 0, 10), TimedWord("  ", 10, 20), TimedWord(" b ", 30, 40), TimedWord("c", 50, 60)]
    assembled = assemble_lines(words, [], FakeOracle())
    assert [w.text for w in assembled.words] == ["a", "b", "c"]
    assert assembled.spans == ((0, 3),)
    check_spans(assembled)


def test_cleaned_words_keep_their_probability():
    words = [TimedWord(" a ", 0, 10, probability=0.5), TimedWord("b", 20, 30, probability=None)]
    assembled = assemble_lines(words, [], FakeOracle())
    assert [w.probability for w in assembled.words] == [0.5, None]


def test_probability_does_not_affect_word_equality():
    assert TimedWord("a", 0, 1, probability=0.2) == TimedWord("a", 0, 1)


def test_no_words_is_still_an_error():
    with pytest.raises(LineAssemblyError):
        assemble_lines([TimedWord(" ", 0, 1)], [], FakeOracle())


def random_take(rng: random.Random) -> tuple[list[TimedWord], list[int]]:
    n = rng.randint(1, 40)
    words: list[TimedWord] = []
    t = rng.randint(0, 500)
    for i in range(n):
        length = rng.randint(50, 600)
        text = "" if rng.random() < 0.05 else f"w{i}"
        words.append(TimedWord(text, t, t + length, probability=rng.random()))
        t += length + rng.choice([0, 20, 80, 150, 400, 900])
    if all(not w.text for w in words):
        words[0] = TimedWord("w0", words[0].start_ms, words[0].end_ms)
    breaks = [rng.randint(0, t + 200) for _ in range(rng.choice([0, 0, 1, 3, 8]))]
    return words, breaks


@pytest.mark.parametrize("seed", range(300))
def test_random_takes_satisfy_the_span_invariants(seed):
    rng = random.Random(seed)
    words, breaks = random_take(rng)
    syllables = {w.text: rng.randint(1, 4) for w in words}
    assembled = assemble_lines(words, breaks, FakeOracle(syllables))
    check_spans(assembled)
    assert [w.text for w in assembled.words] == [w.text.strip() for w in words if w.text.strip()]


def test_every_split_mode_is_reached_by_the_random_takes():
    modes = set()
    for seed in range(300):
        rng = random.Random(seed)
        words, breaks = random_take(rng)
        syllables = {w.text: rng.randint(1, 4) for w in words}
        modes.add(assemble_lines(words, breaks, FakeOracle(syllables)).split_mode)
    assert modes == {SplitMode.taps, SplitMode.auto, SplitMode.single}


def test_long_uniform_take_spans():
    words = timeline(numbered(60))
    check_spans(assemble_lines(words, [], FakeOracle()))
