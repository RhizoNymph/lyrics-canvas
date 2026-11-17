"""Bars, beat units, metric weights and milliseconds (spec sections 5-7), computed by hand."""

from __future__ import annotations

import pytest

from app.services.score import (
    ScoreArgumentError,
    bar_at_tick,
    bar_count,
    bar_length,
    bar_start_tick,
    beat_unit,
    check_score,
    end_tick,
    metric_weight,
    tick_to_ms,
)
from tests.score.helpers import score


def timing_score(meters, tempos=None, pickup=0, notes=None):
    return check_score(score([notes or []], meters=meters, tempos=tempos, pickup=pickup), {"lines": [{"cells": [{"syllable": "x"}] * 4}]})


MIXED = timing_score([(0, 4, 4), (2, 3, 4), (3, 6, 8)], pickup=24)


def test_spec_example_bar_starts_and_lengths():
    assert [bar_start_tick(MIXED, b) for b in range(-1, 6)] == [0, 24, 216, 408, 552, 696, 840]
    assert [bar_length(MIXED, b) for b in range(-1, 5)] == [24, 192, 192, 144, 144, 144]


@pytest.mark.parametrize(
    "tick, expected",
    [(0, (-1, 0)), (10, (-1, 10)), (23, (-1, 23)), (24, (0, 0)), (215, (0, 191)), (216, (1, 0)),
     (408, (2, 0)), (500, (2, 92)), (551, (2, 143)), (552, (3, 0)), (700, (4, 4)), (696 + 144 * 100, (104, 0))],
)
def test_spec_example_bar_at_tick(tick, expected):
    position = bar_at_tick(MIXED, tick)
    assert (position.bar, position.offset) == expected


def test_bar_at_tick_inverts_bar_start_tick():
    for bar in range(0, 50):
        start = bar_start_tick(MIXED, bar)
        assert bar_at_tick(MIXED, start).bar == bar
        assert bar_at_tick(MIXED, start + bar_length(MIXED, bar) - 1).bar == bar


def test_beat_unit_by_meter_in_force():
    assert beat_unit(MIXED, 10) == 48  # pickup uses meters[0]
    assert beat_unit(MIXED, 407) == 48
    assert beat_unit(MIXED, 408) == 48  # 3/4
    assert beat_unit(MIXED, 552) == 24  # 6/8
    assert beat_unit(MIXED, 10**5) == 24
    halves = timing_score([(0, 2, 2), (1, 7, 16), (2, 1, 32), (3, 3, 1)])
    assert [beat_unit(halves, bar_start_tick(halves, b)) for b in range(4)] == [96, 12, 6, 192]


def test_argument_errors():
    with pytest.raises(ScoreArgumentError):
        bar_at_tick(MIXED, -1)
    with pytest.raises(ScoreArgumentError):
        bar_start_tick(MIXED, -2)
    with pytest.raises(ScoreArgumentError):
        tick_to_ms(MIXED, -5)
    with pytest.raises(ScoreArgumentError):
        metric_weight(MIXED, -1)


@pytest.mark.parametrize(
    "meter, expected",  # weights at ticks 0, 6, 12, ..., up to one bar
    [
        ((4, 4), {0: 3, 48: 2, 96: 2, 144: 2, 24: 1, 72: 1, 12: 0, 36: 0, 6: 0, 192: 3}),
        ((3, 4), {0: 3, 48: 2, 96: 2, 24: 1, 120: 1, 144: 3}),
        ((6, 8), {0: 3, 72: 2, 24: 1, 48: 1, 96: 1, 120: 1, 12: 0, 36: 0, 144: 3}),
        ((9, 8), {0: 3, 72: 2, 144: 2, 24: 1, 168: 1, 216: 3}),
        ((12, 8), {0: 3, 72: 2, 144: 2, 216: 2, 24: 1, 12: 0, 288: 3}),
        ((3, 8), {0: 3, 24: 2, 48: 2, 12: 1, 36: 1, 6: 0, 72: 3}),
        ((6, 4), {0: 3, 48: 2, 240: 2, 24: 1, 12: 0}),
        ((5, 8), {0: 3, 24: 2, 96: 2, 12: 1, 6: 0, 120: 3}),
        ((2, 2), {0: 3, 96: 2, 48: 1, 24: 0, 192: 3}),
        ((6, 16), {0: 3, 36: 2, 12: 1, 24: 1, 6: 0, 72: 3}),
        ((1, 32), {0: 3, 3: 1, 6: 3}),
        ((3, 1), {0: 3, 192: 2, 96: 1, 48: 0, 576: 3}),
        ((7, 8), {0: 3, 24: 2, 144: 2, 12: 1, 6: 0, 168: 3}),
    ],
)
def test_metric_weight_by_meter(meter, expected):
    s = timing_score([(0, *meter)])
    assert {tick: metric_weight(s, tick) for tick in expected} == expected


def test_metric_weight_in_the_pickup_uses_a_virtual_full_bar():
    s = timing_score([(0, 4, 4)], pickup=48)
    assert [metric_weight(s, t) for t in (0, 12, 24, 47, 48, 96)] == [2, 0, 1, 0, 3, 2]
    compound = timing_score([(0, 6, 8)], pickup=24)  # the last eighth of a 6/8 bar
    assert [metric_weight(compound, t) for t in (0, 12, 24)] == [1, 0, 3]
    dotted = timing_score([(0, 6, 8)], pickup=72)  # the second dotted quarter
    assert [metric_weight(dotted, t) for t in (0, 24, 72)] == [2, 1, 3]


def test_metric_weight_follows_meter_changes():
    assert metric_weight(MIXED, 552) == 3  # bar 3 (6/8) downbeat
    assert metric_weight(MIXED, 552 + 72) == 2
    assert metric_weight(MIXED, 552 + 48) == 1
    assert metric_weight(MIXED, 408 + 96) == 2  # 3/4 beat 3


def test_tick_to_ms_spec_example():
    s = timing_score([(0, 4, 4)], tempos=[(0, 120), (1, 60)])
    assert tick_to_ms(s, 0) == 0.0
    assert tick_to_ms(s, 96) == 1000.0
    assert tick_to_ms(s, 192) == 2000.0
    assert tick_to_ms(s, 240) == 3000.0


def test_tick_to_ms_pickup_uses_first_tempo():
    s = timing_score([(0, 4, 4)], tempos=[(0, 60), (1, 120)], pickup=48)
    assert tick_to_ms(s, 48) == 1000.0  # pickup quarter at 60
    assert tick_to_ms(s, 240) == 5000.0  # + one 4/4 bar at 60
    assert tick_to_ms(s, 288) == 5500.0  # + a quarter at 120


def test_tick_to_ms_is_monotonic_and_uses_exact_summation():
    s = timing_score([(0, 4, 4), (1, 7, 8)], tempos=[(0, 90.5), (1, 133.33), (5, 20), (6, 400)], pickup=30)
    previous = -1.0
    for tick in range(0, 3000, 7):
        ms = tick_to_ms(s, tick)
        assert ms > previous or tick == 0
        previous = ms
    expected = (30 + 192) * 60000.0 / (90.5 * 48.0)
    assert tick_to_ms(s, 222) == expected


def test_end_tick_and_bar_count():
    assert end_tick(MIXED) == 0 and bar_count(MIXED) == 1
    s = timing_score([(0, 4, 4)], pickup=24, notes=[(0, 0, 12, 0)])
    assert end_tick(s) == 12 and bar_count(s) == 1
    s = timing_score([(0, 4, 4)], pickup=24, notes=[(0, 0, 24, 0)])
    assert bar_count(s) == 1  # ends exactly where bar 0 starts
    s = timing_score([(0, 4, 4)], pickup=24, notes=[(0, 0, 25, 0)])
    assert bar_count(s) == 1
    s = timing_score([(0, 4, 4)], pickup=24, notes=[(0, 200, 16, 0)])
    assert end_tick(s) == 216 and bar_count(s) == 1
    s = timing_score([(0, 4, 4)], pickup=24, notes=[(0, 200, 17, 0)])
    assert bar_count(s) == 2
