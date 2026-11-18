"""apply_grid_rest_edit and is_rest_only_edit (spec section 9)."""

from __future__ import annotations

import pytest

from app.services.score import (
    NotRestOnlyEdit,
    ScoreInvalid,
    ScoreReason,
    apply_grid_rest_edit,
    check_score,
    is_rest_only_edit,
    score_to_json,
)
from app.services.score.constants import MAX_TICKS
from tests.score.helpers import grid, notes_of, score, texts_of


def edit(raw, old, new):
    result = apply_grid_rest_edit(check_score(raw, old), old, new)
    return notes_of(score_to_json(result.score)), texts_of(result.grid)


def test_rest_only_predicate():
    assert is_rest_only_edit(grid("I go"), grid("I _ _ go _"))
    assert is_rest_only_edit(grid("_ I go"), grid("I go"))
    assert not is_rest_only_edit(grid("I go"), grid("I go", "x"))
    assert not is_rest_only_edit(grid("I go"), grid("go I"))
    assert not is_rest_only_edit(grid("I go"), grid("I goes"))
    assert is_rest_only_edit(None, {"lines": []})


def test_spec_example_insert_two_rests():
    raw = score([[(0, 0, 24, 0), (1, 24, 24, 0), (2, 48, 24, 0)]])
    notes, texts = edit(raw, grid("I go now"), grid("I go _ _ now"))
    assert notes == [[(0, 0, 24, 0), (1, 24, 24, 0), (2, 144, 24, 0)]]
    assert texts == ["I go _ _ now"]


def test_spec_example_removal_is_clamped():
    raw = score([[(0, 0, 24, 0), (1, 48, 24, 0), (2, 72, 24, 0)]])
    notes, texts = edit(raw, grid("I _ _ go now"), grid("I go now"))
    assert notes == [[(0, 0, 24, 0), (1, 24, 24, 0), (2, 48, 24, 0)]]
    assert texts == ["I go now"]


def test_removal_within_the_gap_moves_a_full_beat():
    raw = score([[(0, 0, 24, 0), (1, 120, 24, 0)]])  # gap 96 -> 2 rests
    notes, texts = edit(raw, grid("I _ _ go"), grid("I _ go"))
    assert notes == [[(0, 0, 24, 0), (1, 72, 24, 0)]]
    assert texts == ["I _ go"]


def test_grid_rests_out_of_step_with_the_score_use_the_delta():
    # the old grid shows 0 rests although the gap is 2 beats; adding one more pushes by one beat
    raw = score([[(0, 0, 24, 0), (1, 120, 24, 0)]])
    notes, texts = edit(raw, grid("I go"), grid("I _ go"))
    assert notes == [[(0, 0, 24, 0), (1, 168, 24, 0)]]
    assert texts == ["I _ _ _ go"]  # canonical: floor(144/48) = 3


def test_ripple_pushes_later_lines_only_as_far_as_needed():
    old = grid("a b", "c d", "e")
    raw = score([[(0, 0, 24, 0), (1, 24, 24, 0)], [(0, 96, 24, 0), (1, 120, 24, 0)], [(0, 400, 24, 0)]])
    notes, _ = edit(raw, old, grid("a _ _ b", "c d", "e"))
    # b moves to 120 (ends 144); line 2 starts 96 < 144 -> +48: c 144, d 168 (ends 192); e at 400 stays
    assert notes == [[(0, 0, 24, 0), (1, 120, 24, 0)], [(0, 144, 24, 0), (1, 168, 24, 0)], [(0, 400, 24, 0)]]


def test_ripple_never_moves_left():
    old = grid("a b", "c")
    raw = score([[(0, 0, 24, 0), (1, 72, 24, 0)], [(0, 200, 24, 0)]])
    notes, _ = edit(raw, old, grid("a b", "c"))
    assert notes == [[(0, 0, 24, 0), (1, 72, 24, 0)], [(0, 200, 24, 0)]]
    notes, _ = edit(raw, grid("a _ b", "c"), grid("a b", "c"))  # removal: b 72 -> 24
    assert notes == [[(0, 0, 24, 0), (1, 24, 24, 0)], [(0, 200, 24, 0)]]


def test_edits_next_to_unplaced_syllables_move_nothing_and_stay():
    old = grid("a b c")
    raw = score([[(0, 0, 24, 0), (2, 100, 24, 0)]])  # b unplaced
    notes, texts = edit(raw, old, grid("a _ b _ _ c _"))
    assert notes == [[(0, 0, 24, 0), (2, 100, 24, 0)]]
    assert texts == ["a _ b _ _ c _"]


def test_chunk_drag_over_a_rest_moves_only_that_syllable():
    old = grid("a _ b c")
    raw = score([[(0, 0, 24, 0), (1, 72, 24, 0), (2, 96, 24, 0)]])
    # drag b left over the rest: a b _ c
    notes, texts = edit(raw, old, grid("a b _ c"))
    assert notes == [[(0, 0, 24, 0), (1, 24, 24, 0), (2, 96, 24, 0)]]
    assert texts == ["a b _ c"]


def test_several_boundaries_left_to_right_with_current_positions():
    meters = [(0, 4, 4), (1, 6, 8)]
    old = grid("a b c")
    raw = score([[(0, 0, 24, 0), (1, 168, 24, 0), (2, 192, 24, 0)]], meters=meters)
    # boundary 0: +1 at end of a (24, 4/4) -> +48: b 216, c 240; boundary 1: +1 at end of b (240, 6/8) -> +24
    notes, _ = edit(raw, old, grid("a _ b _ c"))
    assert notes == [[(0, 0, 24, 0), (1, 216, 24, 0), (2, 264, 24, 0)]]


def test_not_rest_only_raises():
    raw = score([[(0, 0, 24, 0), (1, 24, 24, 0)]])
    with pytest.raises(NotRestOnlyEdit):
        apply_grid_rest_edit(check_score(raw, grid("I go")), grid("I go"), grid("go I"))


def test_invalid_old_score_raises():
    raw = score([[(0, 0, 24, 0), (1, 24, 24, 0)]])
    with pytest.raises(ScoreInvalid):
        apply_grid_rest_edit(check_score(raw, grid("I go")), grid("I"), grid("I _"))


def test_overflow_refuses_the_edit():
    raw = score([[(0, 0, 24, 0), (1, MAX_TICKS - 24, 24, 0)]])
    with pytest.raises(ScoreInvalid) as info:
        apply_grid_rest_edit(check_score(raw, grid("a b")), grid("a b"), grid("a _ b"))
    assert info.value.reason is ScoreReason.exceeds_max_ticks


def test_extra_keys_and_settings_survive():
    raw = score([[(0, 0, 24, 2), (1, 24, 24, 1)]], tempos=[(0, 77.5)], pickup=12, snap="1/8t")
    raw["lines"][0]["notes"][1]["p"] = 64
    result = apply_grid_rest_edit(check_score(raw, grid("a b")), grid("a b"), grid("a _ b"))
    out = score_to_json(result.score)
    assert out["lines"][0]["notes"][1] == {"s": 1, "t": 72, "d": 24, "a": 1, "p": 64}
    assert (out["tempos"], out["pickup"], out["snap"]) == (raw["tempos"], 12, "1/8t")
