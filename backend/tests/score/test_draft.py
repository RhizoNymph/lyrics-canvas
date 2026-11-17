"""draft_from_grid (spec section 12)."""

from __future__ import annotations

import pytest

from app.services.score import (
    ScoreInvalid,
    ScoreReason,
    apply_rest_link,
    check_score,
    draft_from_grid,
    score_to_json,
)
from tests.score.helpers import grid, notes_of


def draft(g, num=4, den=4, bpm=90, note="1/8"):
    return score_to_json(draft_from_grid(g, {"num": num, "den": den}, bpm, note))


def test_spec_example():
    out = draft(grid("^I _ ^go", "^now"))
    assert notes_of(out) == [[(0, 0, 24, 1), (1, 72, 24, 1)], [(0, 192, 24, 1)]]
    assert out["meters"] == [{"bar": 0, "num": 4, "den": 4}]
    assert out["tempos"] == [{"bar": 0, "bpm": 90}]
    assert (out["pickup"], out["snap"]) == (0, "1/8")


def test_stress_to_accent():
    g = {"lines": [{"cells": [{"syllable": "a", "stress": 1}, {"syllable": "b", "stress": 2}, {"syllable": "c", "stress": 0}, {"syllable": "d"}], "raw_text": "a b c d"}]}
    assert [n["a"] for n in draft(g)["lines"][0]["notes"]] == [1, 0, 0, 0]


@pytest.mark.parametrize("note, ticks", [("1/8", 24), ("1/16", 12), ("1/32", 6), ("1/8t", 16), ("1/16t", 8)])
def test_note_lengths(note, ticks):
    out = draft(grid("a b c"), note=note)
    assert notes_of(out) == [[(0, 0, ticks, 0), (1, ticks, ticks, 0), (2, 2 * ticks, ticks, 0)]]


def test_lines_start_on_the_next_bar():
    # 3/4 bar = 144; 1/8 notes: 6 syllables = 144 exactly -> next line at 144, 7 -> 288
    out = draft(grid("a b c d e f", "g", "h i j k l m n", "o"), num=3, den=4)
    starts = [ln["notes"][0]["t"] for ln in out["lines"]]
    assert starts == [0, 144, 288, 576]


def test_rest_is_one_beat_unit_and_leading_trailing_rests_count():
    out = draft(grid("_ a _ b _ _", "c"), num=6, den=8, note="1/16")
    # beat 24: a at 24, rest 36..60, b at 60 ends 72, two rests -> 120; bar 144 -> c at 144
    assert notes_of(out) == [[(0, 24, 12, 0), (1, 60, 12, 0)], [(0, 144, 12, 0)]]


def test_rest_only_and_empty_lines():
    g = {"lines": [{"cells": [{"rest": True}] * 5, "raw_text": "_ _ _ _ _"}, {"cells": [], "raw_text": ""}, {"cells": [{"syllable": "x"}], "raw_text": "x"}]}
    out = draft(g)
    assert notes_of(out) == [[], [], [(0, 384, 24, 0)]]


def test_empty_and_null_grid():
    assert draft(None)["lines"] == []
    assert draft({"lines": []})["lines"] == []


@pytest.mark.parametrize(
    "kwargs, reason",
    [
        ({"num": 0}, ScoreReason.meter_invalid),
        ({"den": 3}, ScoreReason.meter_invalid),
        ({"num": "4"}, ScoreReason.meter_invalid),
        ({"bpm": 19}, ScoreReason.tempo_invalid),
        ({"bpm": 90.123}, ScoreReason.tempo_invalid),
        ({"bpm": None}, ScoreReason.tempo_invalid),
        ({"note": "1/4"}, ScoreReason.snap_invalid),
    ],
)
def test_argument_errors(kwargs, reason):
    with pytest.raises(ScoreInvalid) as info:
        draft(grid("a"), **kwargs)
    assert info.value.reason is reason


def test_overflow():
    g = {"lines": [{"cells": [{"syllable": "x"}] * 20000, "raw_text": "x"}]}
    with pytest.raises(ScoreInvalid) as info:
        draft(g, note="1/8")
    assert info.value.reason is ScoreReason.exceeds_max_ticks


@pytest.mark.parametrize("meter", [(4, 4), (3, 4), (6, 8), (7, 8), (2, 2), (5, 16), (12, 8), (1, 1)])
@pytest.mark.parametrize("note", ["1/8", "1/16", "1/32", "1/8t", "1/16t"])
def test_round_trip_through_the_rest_link(meter, note):
    g = grid("_ a _ b-c _ _ d", "e", "_ _", "f _ g _ _ _ h _")
    s = draft_from_grid(g, {"num": meter[0], "den": meter[1]}, 120, note)
    assert apply_rest_link(check_score(score_to_json(s), g), g) == g
