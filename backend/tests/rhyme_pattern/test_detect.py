"""Pattern detection over hand-written lines."""

import random
import time

import pytest

from app.services.phonetics import DEFAULT_DIALECT_MAP
from app.services.phonetics.pattern.detect import detect
from app.services.phonetics.pattern.types import default_on

from .helpers import EXAMPLE, assert_slot_invariants, line_words, texts


def _detect(lines, **kw):
    return detect(line_words(lines), dialect=DEFAULT_DIALECT_MAP, **kw)


def test_example_lines_find_three_full_slots_in_order():
    slots = _detect(EXAMPLE)
    assert_slot_invariants(slots, 0.75)
    assert [texts(s) for s in slots] == [
        ["steady", "already", "ready"],
        ["pressure", "weather", "better"],
        ["on", "wrong", "long"],
    ]
    assert all(s.full and s.default_on and s.coverage == 3 for s in slots)


def test_example_slot_shapes():
    steady, eh_er, end = _detect(EXAMPLE)
    assert steady.k == 2 and steady.stress == "10" and steady.syllable_count == 2
    assert eh_er.k == 2 and eh_er.stress == "10"
    assert end.k == 1 and end.stress == "1" and end.syllable_count == 1
    assert 0.75 <= end.cohesion < 1.0
    assert steady.cohesion == pytest.approx(1.0)


def test_detection_is_deterministic():
    first = _detect(EXAMPLE)
    for _ in range(3):
        again = _detect(EXAMPLE)
        assert [[(m.pos, m.unit.id) for m in s.members] for s in again] == [
            [(m.pos, m.unit.id) for m in s.members] for s in first
        ]
        assert [(s.k, s.cohesion, s.stress) for s in again] == [(s.k, s.cohesion, s.stress) for s in first]


def test_a_line_breaking_the_pattern_leaves_a_partial_slot():
    lines = (EXAMPLE[0], EXAMPLE[1], "ready or not it's over long")
    slots = _detect(lines)
    assert_slot_invariants(slots, 0.75)
    by_text = {texts(s)[0]: s for s in slots}
    eh_er = by_text["pressure"]
    assert texts(eh_er) == ["pressure", "weather"]
    assert eh_er.coverage == 2 and eh_er.full is False and eh_er.default_on is False
    assert by_text["steady"].full and by_text["on"].full
    assert list(by_text) == ["steady", "pressure", "on"]


def test_partial_slot_is_on_by_default_with_four_lines():
    lines = (*EXAMPLE, "heavy as a feather and a stone")
    slots = _detect(lines)
    assert_slot_invariants(slots, 0.75)
    end = next(s for s in slots if texts(s)[0] == "on")
    assert end.coverage == 3 and end.full is False and end.default_on is True


def test_default_on_rule():
    assert default_on(2, 2) and default_on(3, 3)
    assert not default_on(2, 3)
    assert default_on(3, 4) and not default_on(2, 4)
    assert default_on(6, 8) and not default_on(5, 8)
    assert default_on(12, 16) and not default_on(11, 16)


def test_no_shared_rhyme_no_slots():
    assert _detect(("the cat sat down", "a big blue tree")) == ()


def test_function_words_never_form_a_slot_alone():
    assert _detect(("the dog of the night", "a fish in the sea")) == ()


def test_crossing_rhymes_do_not_make_two_crossing_slots():
    slots = _detect(("cat in the night", "light on the hat"))
    assert_slot_invariants(slots)
    found = [set(texts(s)) for s in slots]
    assert not ({"cat", "hat"} in found and {"night", "light"} in found)
    assert len(slots) == 1


def test_at_most_eight_slots():
    words = "cat bat hat mat rat sat fat pat vat gnat"
    slots = _detect((words, words))
    assert len(slots) == 8
    assert_slot_invariants(slots, 0.75)


def test_threshold_controls_detection():
    strict = _detect(EXAMPLE, min_score=1.0)
    assert_slot_invariants(strict, 1.0)
    assert [texts(s) for s in strict] == [["steady", "already", "ready"], ["wrong", "long"]]


def test_spans_compete_by_score_then_rhyme_depth_not_by_extra_words():
    # "stay strong" scores like "strong" against "along" (one rhyming syllable); the shorter span wins
    slots = _detect(("we stay strong", "we go along"))
    assert [texts(s) for s in slots] == [["strong", "along"]]


def test_multi_word_spans_win_when_they_rhyme_deeper():
    slots = _detect(("pressure on", "weather wrong", "better long"))
    assert_slot_invariants(slots, 0.75)
    # two slots (EH-er, AA-n) beat one three-syllable slot
    assert [texts(s) for s in slots] == [["pressure", "weather", "better"], ["on", "wrong", "long"]]


def test_lines_without_spans_are_ignored():
    slots = _detect(("I keep it steady", "_", "already ready"))
    assert_slot_invariants(slots)
    assert all(m.pos.line_index != 1 for s in slots for m in s.members)


VERSE_VOCAB = (
    "I you the a to and of in on my it is we be your me at or an love night light fight right stay strong "
    "long wrong song along gone steady ready heavy pressure weather better letter money honey city pity heart "
    "start apart dark park mark time mind find kind fire higher desire rain pain again chain dream team seem "
    "street feet beat heat cold gold hold soul road know show flow grow"
).split()


@pytest.mark.timing
def test_sixteen_lines_of_sixteen_words_under_50ms():
    rng = random.Random(7)
    lines = [" ".join(rng.choice(VERSE_VOCAB) for _ in range(16)) for _ in range(16)]
    words = line_words(lines)
    detect(words, dialect=DEFAULT_DIALECT_MAP)  # warm the pronunciation caches
    timings = []
    for _ in range(3):
        started = time.perf_counter()
        slots = detect(words, dialect=DEFAULT_DIALECT_MAP)
        timings.append((time.perf_counter() - started) * 1000)
    assert len(slots) == 8
    assert_slot_invariants(slots, 0.75)
    assert sorted(timings)[1] < 50, timings
