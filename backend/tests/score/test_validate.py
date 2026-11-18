"""validate (spec section 4): every reason, the check order and the integer rules."""

from __future__ import annotations

import copy

import pytest

from app.services.score import Invalid, ScoreInvalid, ScoreReason, Valid, check_score, score_to_json, validate
from app.services.score.constants import MAX_TICKS
from tests.score.helpers import grid, score

R = ScoreReason
G2 = grid("I go")  # one line, 2 syllables
OK = score([[(0, 0, 12, 1), (1, 12, 12, 0)]])


def reason_of(raw, g=G2):
    result = validate(raw, g)
    assert isinstance(result, Invalid), result
    return result.error.reason, result.error.line, result.error.note


def with_(raw, **changes):
    out = copy.deepcopy(raw)
    out.update(changes)
    return out


def test_ok_round_trips():
    result = validate(OK, G2)
    assert isinstance(result, Valid)
    assert score_to_json(result.score) == OK


def test_check_score_raises_typed():
    with pytest.raises(ScoreInvalid) as info:
        check_score(with_(OK, v=2), G2)
    assert info.value.reason is R.unsupported_version
    assert info.value.as_dict() == {"error": "invalid_score", "reason": "unsupported_version"}


@pytest.mark.parametrize("raw", [None, [], "score", 1, True])
def test_not_an_object(raw):
    assert reason_of(raw) == (R.malformed, None, None)


def test_missing_and_unknown_keys():
    missing = copy.deepcopy(OK)
    del missing["snap"]
    assert reason_of(missing)[0] is R.malformed
    assert reason_of(with_(OK, extra=1))[0] is R.malformed


@pytest.mark.parametrize(
    "changes, expected",
    [
        ({"v": "1"}, R.malformed),
        ({"v": True}, R.malformed),
        ({"v": 2}, R.unsupported_version),
        ({"v": 0}, R.unsupported_version),
        ({"ppq": 96}, R.bad_ppq),
        ({"ppq": 48.5}, R.malformed),
        ({"meters": {}}, R.malformed),
        ({"meters": []}, R.meters_empty),
        ({"meters": [{"bar": 0, "num": 4, "den": 4}] + [{"bar": i, "num": 4, "den": 4} for i in range(1, 257)]}, R.too_many_meters),
        ({"meters": [{"bar": 0, "num": 4}]}, R.malformed),
        ({"meters": [{"bar": 0, "num": 4, "den": 4, "x": 1}]}, R.malformed),
        ({"meters": [{"bar": 1, "num": 4, "den": 4}]}, R.meters_first_not_zero),
        ({"meters": [{"bar": -1, "num": 4, "den": 4}]}, R.meters_first_not_zero),
        ({"meters": [{"bar": 0, "num": 4, "den": 4}, {"bar": 0, "num": 3, "den": 4}]}, R.meters_not_increasing),
        ({"meters": [{"bar": 0, "num": 4, "den": 4}, {"bar": 3, "num": 3, "den": 4}, {"bar": 2, "num": 3, "den": 4}]}, R.meters_not_increasing),
        ({"meters": [{"bar": 0, "num": 4, "den": 4}, {"bar": 2049, "num": 3, "den": 4}]}, R.bar_out_of_range),
        ({"meters": [{"bar": 0, "num": 0, "den": 4}]}, R.meter_invalid),
        ({"meters": [{"bar": 0, "num": 33, "den": 4}]}, R.meter_invalid),
        ({"meters": [{"bar": 0, "num": 4, "den": 3}]}, R.meter_invalid),
        ({"meters": [{"bar": 0, "num": 4, "den": 64}]}, R.meter_invalid),
        ({"tempos": None}, R.malformed),
        ({"tempos": []}, R.tempos_empty),
        ({"tempos": [{"bar": i, "bpm": 90} for i in range(257)]}, R.too_many_tempos),
        ({"tempos": [{"bar": 0, "bpm": "90"}]}, R.malformed),
        ({"tempos": [{"bar": 0, "bpm": True}]}, R.malformed),
        ({"tempos": [{"bar": 0.5, "bpm": 90}]}, R.malformed),
        ({"tempos": [{"bar": 2, "bpm": 90}]}, R.tempos_first_not_zero),
        ({"tempos": [{"bar": 0, "bpm": 90}, {"bar": 0, "bpm": 91}]}, R.tempos_not_increasing),
        ({"tempos": [{"bar": 0, "bpm": 90}, {"bar": 2049, "bpm": 91}]}, R.bar_out_of_range),
        ({"tempos": [{"bar": 0, "bpm": 19.99}]}, R.tempo_invalid),
        ({"tempos": [{"bar": 0, "bpm": 400.01}]}, R.tempo_invalid),
        ({"tempos": [{"bar": 0, "bpm": 90.155}]}, R.tempo_invalid),
        ({"pickup": -1}, R.bad_pickup),
        ({"pickup": 192}, R.bad_pickup),
        ({"pickup": "0"}, R.malformed),
        ({"snap": "1/4"}, R.snap_invalid),
        ({"snap": None}, R.snap_invalid),
        ({"snap": "1/16T"}, R.snap_invalid),
        ({"lines": {}}, R.malformed),
        ({"lines": []}, R.line_count_mismatch),
        ({"lines": [{"notes": []}, {"notes": []}]}, R.line_count_mismatch),
    ],
)
def test_top_level_reasons(changes, expected):
    assert reason_of(with_(OK, **changes)) == (expected, None, None)


def test_meter_and_tempo_limits_inclusive():
    meters = [{"bar": i, "num": 4, "den": 4} for i in range(255)] + [{"bar": 2048, "num": 32, "den": 32}]
    tempos = [{"bar": 0, "bpm": 20}, {"bar": 1, "bpm": 400}, {"bar": 2048, "bpm": 90.15}]
    assert isinstance(validate(with_(OK, meters=meters, tempos=tempos), G2), Valid)


@pytest.mark.parametrize("den", [1, 2, 4, 8, 16, 32])
def test_every_denominator(den):
    assert isinstance(validate(with_(OK, meters=[{"bar": 0, "num": 1, "den": den}], pickup=0), G2), Valid)


def test_pickup_bounds_follow_first_meter():
    meters = [{"bar": 0, "num": 3, "den": 8}, {"bar": 1, "num": 4, "den": 4}]
    assert isinstance(validate(with_(OK, meters=meters, pickup=71), G2), Valid)
    assert reason_of(with_(OK, meters=meters, pickup=72))[0] is R.bad_pickup


@pytest.mark.parametrize(
    "lines, expected",
    [
        ([{"notes": [{"s": 0, "t": 0, "d": 1}]}], (R.malformed, 0, 0)),
        ([{"notes": [{"s": 0, "t": 0, "d": 1, "a": 0.5}]}], (R.malformed, 0, 0)),
        ([{"notes": [{"s": 0, "t": 0, "d": 1, "a": 0}, 5]}], (R.malformed, 0, 1)),
        ([{"notes": {}}], (R.malformed, 0, None)),
        ([{"notes": [], "x": 1}], (R.malformed, 0, None)),
        ([[]], (R.malformed, 0, None)),
        ([{"notes": [{"s": 2, "t": 0, "d": 1, "a": 0}]}], (R.syllable_out_of_range, 0, 0)),
        ([{"notes": [{"s": -1, "t": 0, "d": 1, "a": 0}]}], (R.syllable_out_of_range, 0, 0)),
        ([{"notes": [{"s": 1, "t": 0, "d": 1, "a": 0}, {"s": 1, "t": 5, "d": 1, "a": 0}]}], (R.syllables_not_increasing, 0, 1)),
        ([{"notes": [{"s": 1, "t": 0, "d": 1, "a": 0}, {"s": 0, "t": 5, "d": 1, "a": 0}]}], (R.syllables_not_increasing, 0, 1)),
        ([{"notes": [{"s": 0, "t": -1, "d": 1, "a": 0}]}], (R.start_negative, 0, 0)),
        ([{"notes": [{"s": 0, "t": 0, "d": 0, "a": 0}]}], (R.duration_invalid, 0, 0)),
        ([{"notes": [{"s": 0, "t": 0, "d": 1, "a": 3}]}], (R.accent_invalid, 0, 0)),
        ([{"notes": [{"s": 0, "t": 0, "d": 1, "a": -1}]}], (R.accent_invalid, 0, 0)),
        ([{"notes": [{"s": 0, "t": MAX_TICKS, "d": 1, "a": 0}]}], (R.exceeds_max_ticks, 0, 0)),
        ([{"notes": [{"s": 0, "t": 0, "d": 24, "a": 0}, {"s": 1, "t": 12, "d": 12, "a": 0}]}], (R.notes_overlap, 0, 1)),
        ([{"notes": [{"s": 0, "t": 20, "d": 4, "a": 0}, {"s": 1, "t": 0, "d": 4, "a": 0}]}], (R.notes_overlap, 0, 1)),
    ],
)
def test_note_reasons(lines, expected):
    assert reason_of(with_(OK, lines=lines)) == expected


def test_note_check_order_within_a_note():
    # s out of range is reported before a bad duration and a bad accent of the same note.
    raw = with_(OK, lines=[{"notes": [{"s": 9, "t": -1, "d": 0, "a": 7}]}])
    assert reason_of(raw)[0] is R.syllable_out_of_range
    raw = with_(OK, lines=[{"notes": [{"s": 0, "t": -1, "d": 0, "a": 7}]}])
    assert reason_of(raw)[0] is R.start_negative


def test_top_level_order():
    # meters are checked before tempos, tempos before pickup, pickup before snap, snap before lines.
    raw = with_(OK, meters=[], tempos=[], pickup=-1, snap="x", lines=[])
    assert reason_of(raw)[0] is R.meters_empty
    raw = with_(OK, tempos=[], pickup=-1, snap="x", lines=[])
    assert reason_of(raw)[0] is R.tempos_empty
    raw = with_(OK, pickup=-1, snap="x", lines=[])
    assert reason_of(raw)[0] is R.bad_pickup
    raw = with_(OK, snap="x", lines=[])
    assert reason_of(raw)[0] is R.snap_invalid
    assert reason_of(with_(OK, v=2, ppq=1))[0] is R.unsupported_version


def test_overlap_across_lines():
    g = grid("I go", "now")
    raw = score([[(0, 0, 12, 0), (1, 12, 24, 0)], [(0, 30, 12, 0)]])
    assert reason_of(raw, g) == (R.notes_overlap, 1, 0)
    touching = score([[(0, 0, 12, 0), (1, 12, 24, 0)], [(0, 36, 12, 0)]])
    assert isinstance(validate(touching, g), Valid)


def test_note_ending_exactly_at_max_ticks():
    raw = with_(OK, lines=[{"notes": [{"s": 0, "t": MAX_TICKS - 1, "d": 1, "a": 2}]}])
    assert isinstance(validate(raw, G2), Valid)


@pytest.mark.parametrize("value", [12.0, -0.0])
def test_integral_floats_are_integers(value):
    raw = with_(OK, lines=[{"notes": [{"s": 0, "t": value, "d": 12.0, "a": 1.0}]}])
    result = validate(raw, G2)
    assert isinstance(result, Valid)
    note = result.score.lines[0].notes[0]
    assert type(note.t) is int and type(note.d) is int and note.d == 12


@pytest.mark.parametrize("value", [12.5, True, False, "12", None, 1e20, 2**53, [1], {"x": 1}])
def test_non_integers_are_malformed(value):
    raw = with_(OK, lines=[{"notes": [{"s": 0, "t": value, "d": 12, "a": 1}]}])
    assert reason_of(raw) == (R.malformed, 0, 0)


def test_largest_safe_integer_is_an_integer():
    raw = with_(OK, lines=[{"notes": [{"s": 0, "t": 2**53 - 1, "d": 1, "a": 0}]}])
    assert reason_of(raw) == (R.exceeds_max_ticks, 0, 0)


def test_extra_note_keys_are_preserved():
    raw = with_(OK, lines=[{"notes": [{"s": 0, "t": 0, "d": 12, "a": 1, "p": 60, "meta": {"x": [1]}}]}])
    result = validate(raw, G2)
    assert isinstance(result, Valid)
    assert score_to_json(result.score) == raw
    assert list(score_to_json(result.score)["lines"][0]["notes"][0]) == ["s", "t", "d", "a", "p", "meta"]


def test_null_grid_is_zero_lines():
    assert isinstance(validate(with_(OK, lines=[]), None), Valid)
    assert reason_of(OK, None)[0] is R.line_count_mismatch


def test_rest_only_line_has_no_syllables():
    g = grid("_ _", "go")
    assert reason_of(score([[(0, 0, 1, 0)], []]), g) == (R.syllable_out_of_range, 0, 0)
    assert isinstance(validate(score([[], [(0, 0, 1, 0)]]), g), Valid)


def test_syllable_count_comes_from_cells_not_the_stored_count():
    g = grid("I go")
    g["lines"][0]["syllable_count"] = 7
    assert reason_of(score([[(2, 0, 1, 0)]]), g)[0] is R.syllable_out_of_range


def test_bpm_two_decimals():
    for bpm in (90, 90.5, 90.15, 120.01, 33.33, 399.99):
        assert isinstance(validate(with_(OK, tempos=[{"bar": 0, "bpm": bpm}]), G2), Valid), bpm
    for bpm in (90.001, 33.333, 120.125):
        assert reason_of(with_(OK, tempos=[{"bar": 0, "bpm": bpm}]))[0] is R.tempo_invalid, bpm


def test_every_reason_is_reachable_by_this_file():
    # Guard: when a reason is added to the enum, add a test for it above.
    assert len(ScoreReason) == 24
