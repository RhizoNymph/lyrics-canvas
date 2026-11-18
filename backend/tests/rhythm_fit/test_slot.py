"""Slot rhythm from a score and grid: ordinals, onsets, weights, accents, unavailable reasons."""

from __future__ import annotations

import logging

from app.services.rhythm_fit import RhythmUnavailable, SlotBeat, SlotRhythm, slot_ordinals, slot_rhythm, stored_slot_rhythm
from app.services.score import check_score
from tests.score.helpers import grid, score

# line 0: hel lo _ world   (ordinals 0, 1, 2; word_index 0, 0, 1)
# line 1: _ the ^night     (ordinals 0, 1; word_index 0, 1)
GRID = grid("^hel-lo _ ^world", "_ the ^night")
FULL = score([[(0, 0, 24, 1), (1, 24, 24, 0), (2, 96, 48, 1)], [(0, 216, 12, 0), (1, 240, 48, 2)]])
PARTIAL = score([[(0, 0, 24, 1), (1, 24, 24, 0)], [(1, 240, 48, 0)]])


def test_ordinals_skip_rests_and_follow_the_word_range():
    line0, line1 = GRID["lines"]
    assert slot_ordinals(line0, 0, None) == [0, 1, 2]
    assert slot_ordinals(line0, 0, 1) == [0, 1]
    assert slot_ordinals(line0, 1, None) == [2]
    assert slot_ordinals(line0, 1, 2) == [2]
    assert slot_ordinals(line0, 2, None) == []
    assert slot_ordinals(line1, 1, None) == [1]


def test_whole_line_slot_reads_onsets_weights_and_accents():
    s = check_score(FULL, GRID)
    assert slot_rhythm(s, GRID, 0, 0, None) == SlotRhythm(
        (SlotBeat(onset=0, weight=3, accent=1), SlotBeat(onset=24, weight=1, accent=0), SlotBeat(onset=96, weight=2, accent=1))
    )


def test_word_range_slot():
    s = check_score(FULL, GRID)
    assert slot_rhythm(s, GRID, 0, 1, None) == SlotRhythm((SlotBeat(96, 2, 1),))
    assert slot_rhythm(s, GRID, 0, 0, 1) == SlotRhythm((SlotBeat(0, 3, 1), SlotBeat(24, 1, 0)))
    # line 1 starts on bar 1 (tick 192): "the" on 216 is a half beat, "night" on 240 a beat, accent 2
    assert slot_rhythm(s, GRID, 1, 0, None) == SlotRhythm((SlotBeat(216, 1, 0), SlotBeat(240, 2, 2)))


def test_a_pickup_onset_weighs_as_the_tail_of_a_virtual_bar():
    g = grid("a ^way")
    s = check_score(score([[(0, 0, 48, 0), (1, 48, 48, 1)]], pickup=48), g)
    assert slot_rhythm(s, g, 0, 0, None) == SlotRhythm((SlotBeat(0, 2, 0), SlotBeat(48, 3, 1)))


def test_any_unplaced_slot_syllable_makes_the_slot_unplaced():
    s = check_score(PARTIAL, GRID)
    assert slot_rhythm(s, GRID, 0, 0, None) == RhythmUnavailable("slot_unplaced")
    assert slot_rhythm(s, GRID, 0, 1, None) == RhythmUnavailable("slot_unplaced")
    assert isinstance(slot_rhythm(s, GRID, 0, 0, 1), SlotRhythm)
    assert slot_rhythm(s, GRID, 1, 0, None) == RhythmUnavailable("slot_unplaced")
    assert slot_rhythm(s, GRID, 1, 1, None) == SlotRhythm((SlotBeat(240, 2, 0),))


def test_a_range_without_syllables_or_a_missing_line_is_unplaced():
    s = check_score(FULL, GRID)
    assert slot_rhythm(s, GRID, 0, 2, None) == RhythmUnavailable("slot_unplaced")
    assert slot_rhythm(s, GRID, 2, 0, None) == RhythmUnavailable("slot_unplaced")
    assert slot_rhythm(s, GRID, -1, 0, None) == RhythmUnavailable("slot_unplaced")


def test_a_line_of_rests_only_is_unplaced():
    g = grid("_ _", "go")
    s = check_score(score([[], [(0, 192, 48, 1)]]), g)
    assert slot_rhythm(s, g, 0, 0, None) == RhythmUnavailable("slot_unplaced")
    assert slot_rhythm(s, g, 1, 0, None) == SlotRhythm((SlotBeat(192, 3, 1),))


def test_stored_score_none_is_no_score():
    assert stored_slot_rhythm(None, GRID, 0, 0, None) == RhythmUnavailable("no_score")


def test_stored_score_is_validated_and_read():
    assert stored_slot_rhythm(FULL, GRID, 0, 1, None) == SlotRhythm((SlotBeat(96, 2, 1),))


def test_an_invalid_stored_score_is_logged_and_treated_as_no_score(caplog):
    broken = {**FULL, "lines": FULL["lines"][:1]}  # line_count_mismatch
    with caplog.at_level(logging.WARNING, logger="app.services.rhythm_fit.slot"):
        assert stored_slot_rhythm(broken, GRID, 0, 0, None, snippet_id="s1") == RhythmUnavailable("no_score")
    assert "rhythm_fit.stored_score_invalid snippet_id=s1 reason=line_count_mismatch" in caplog.text
