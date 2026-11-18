"""The rhythm-fit score, computed by hand (docs/features/rhyme_rhythm_fit.md#the-score)."""

from __future__ import annotations

from fractions import Fraction

import pytest

from app.services.rhythm_fit import (
    ACCENT_LEVEL,
    SlotBeat,
    SlotRhythm,
    StressPatternError,
    SyllableCountMismatch,
    candidate_stresses,
    rhythm_fit,
    rhythm_fit_exact,
    slot_targets,
    wire_fit,
)


def slot(*beats: tuple[int, int]) -> SlotRhythm:
    """``(metric weight, accent)`` per syllable; onsets are irrelevant to the score."""
    return SlotRhythm(tuple(SlotBeat(onset=i * 24, weight=w, accent=a) for i, (w, a) in enumerate(beats)))


def fit(s: SlotRhythm, stress: str) -> float:
    return rhythm_fit(s, candidate_stresses(stress))


# --- levels and targets ---


def test_accent_levels_raise_never_lower():
    assert ACCENT_LEVEL == {0: 0, 1: 2, 2: 3}
    assert SlotBeat(0, weight=0, accent=0).level == 0
    assert SlotBeat(0, weight=0, accent=1).level == 2
    assert SlotBeat(0, weight=1, accent=1).level == 2
    assert SlotBeat(0, weight=0, accent=2).level == 3
    assert SlotBeat(0, weight=3, accent=0).level == 3
    assert SlotBeat(0, weight=3, accent=1).level == 3


@pytest.mark.parametrize("weight, accent", [(-1, 0), (4, 0), (0, 3), (0, -1)])
def test_slot_beat_rejects_out_of_range_values(weight, accent):
    with pytest.raises(ValueError):
        SlotBeat(0, weight=weight, accent=accent)


def test_slot_rhythm_is_never_empty():
    with pytest.raises(ValueError):
        SlotRhythm(())


def test_targets_are_min_max_normalized_when_levels_differ():
    assert slot_targets(slot((3, 0), (0, 0))) == (1, 0)
    assert slot_targets(slot((2, 0), (3, 0))) == (0, 1)
    assert slot_targets(slot((3, 0), (0, 0), (2, 0))) == (1, 0, Fraction(2, 3))
    assert slot_targets(slot((2, 0), (1, 0))) == (1, 0)


def test_targets_without_contrast_keep_absolute_strength():
    assert slot_targets(slot((3, 0))) == (1,)
    assert slot_targets(slot((0, 0))) == (0,)
    assert slot_targets(slot((2, 0))) == (Fraction(2, 3),)
    assert slot_targets(slot((1, 0), (1, 0))) == (Fraction(1, 3), Fraction(1, 3))


# --- the score ---


@pytest.mark.parametrize(
    "stress, expected",
    [("10", 1.0), ("01", 0.0), ("12", 0.75), ("20", 0.75), ("11", 0.5), ("00", 0.5), ("21", 0.25)],
)
def test_downbeat_then_offbeat_wants_a_trochee(stress, expected):
    assert fit(slot((3, 0), (0, 0)), stress) == expected


@pytest.mark.parametrize("stress, expected", [("01", 1.0), ("10", 0.0), ("02", 0.75)])
def test_pickup_beat_into_a_downbeat_wants_an_iamb(stress, expected):
    assert fit(slot((2, 0), (3, 0)), stress) == expected


@pytest.mark.parametrize(
    "stress, expected",
    [("102", 0.9444), ("101", 0.8889), ("100", 0.7778), ("110", 0.4444), ("010", 0.1111)],
)
def test_three_syllables_downbeat_sixteenth_beat(stress, expected):
    # targets 1, 0, 2/3: secondary stress (1/2) sits closest to the beat's 2/3
    assert fit(slot((3, 0), (0, 0), (2, 0)), stress) == expected


@pytest.mark.parametrize(
    "beat, stress, expected",
    [((3, 0), "1", 1.0), ((3, 0), "0", 0.0), ((0, 0), "1", 0.0), ((0, 0), "0", 1.0),
     ((2, 0), "1", 0.6667), ((2, 0), "2", 0.8333), ((2, 0), "0", 0.3333), ((0, 2), "1", 1.0)],
)
def test_single_syllable_uses_absolute_strength(beat, stress, expected):
    assert fit(slot(beat), stress) == expected


def test_an_accent_makes_a_syncopated_offbeat_strong():
    # an accented sixteenth before an unaccented eighth: the accent is where the stress belongs
    assert fit(slot((0, 2), (1, 0)), "10") == 1.0
    assert fit(slot((0, 2), (1, 0)), "01") == 0.0
    # accent 1 counts like a beat (level 2), still below a downbeat
    assert slot_targets(slot((0, 1), (3, 0))) == (0, 1)


def test_fit_is_always_within_zero_and_one():
    stresses = ["0", "1", "2"]
    for w1 in range(4):
        for a1 in range(3):
            for w2 in range(4):
                for a2 in range(3):
                    s = slot((w1, a1), (w2, a2))
                    for x in stresses:
                        for y in stresses:
                            value = rhythm_fit_exact(s, candidate_stresses(x + y))
                            assert 0 <= value <= 1


def test_fit_is_rounded_to_four_decimals_once():
    assert wire_fit(Fraction(1, 3)) == 0.3333
    assert wire_fit(Fraction(2, 3)) == 0.6667
    assert rhythm_fit_exact(slot((3, 0), (0, 0), (2, 0)), (1, 0, 2)) == Fraction(17, 18)


def test_syllable_count_mismatch_is_typed():
    with pytest.raises(SyllableCountMismatch) as info:
        rhythm_fit(slot((3, 0), (0, 0)), (1,))
    assert (info.value.expected, info.value.got) == (2, 1)


# --- candidate stress strings ---


def test_candidate_stresses_read_digits():
    assert candidate_stresses("102") == (1, 0, 2)
    assert candidate_stresses("") == ()


def test_rests_in_a_candidate_stress_string_are_not_syllables():
    assert candidate_stresses("1_0") == (1, 0)
    assert candidate_stresses("_1__0_") == (1, 0)
    assert fit(slot((3, 0), (0, 0)), "1_0") == 1.0


@pytest.mark.parametrize("stress, position", [("1x0", 1), ("3", 0), ("10 ", 2)])
def test_invalid_stress_characters_are_typed(stress, position):
    with pytest.raises(StressPatternError) as info:
        candidate_stresses(stress)
    assert info.value.position == position
