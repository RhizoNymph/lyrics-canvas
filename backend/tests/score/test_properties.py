"""Property-style tests over many deterministic random grids, scores and edits."""

from __future__ import annotations

import pytest

from app.services.score import (
    NotRestOnlyEdit,
    ScoreInvalid,
    apply_grid_rest_edit,
    apply_rest_link,
    bar_at_tick,
    bar_start_tick,
    check_score,
    draft_from_grid,
    is_rest_only_edit,
    reconcile,
    score_to_json,
    validate,
)
from app.services.score.grid_view import grid_lines, line_cells, syllable_identity
from app.services.score.validate import Valid
from scripts.score_vector_random import Lcg, random_grid, random_score, rest_edit, text_edit

SEEDS = range(300)


def world(seed):
    rng = Lcg(seed * 7919 + 1)
    g = random_grid(rng)
    raw = random_score(rng, g, place_percent=rng.pick((40, 80, 100)))
    return rng, g, check_score(raw, g)


@pytest.mark.parametrize("seed", SEEDS)
def test_rest_link_keeps_validity_syllables_and_is_idempotent(seed):
    _, g, s = world(seed)
    linked = apply_rest_link(s, g)
    assert isinstance(validate(score_to_json(s), linked), Valid)
    assert is_rest_only_edit(g, linked)
    for old, new in zip(grid_lines(g), grid_lines(linked), strict=True):
        assert syllable_identity(line_cells(old)) == syllable_identity(line_cells(new))
        assert {k: v for k, v in old.items() if k not in ("cells", "raw_text")} == {k: v for k, v in new.items() if k not in ("cells", "raw_text")}
    assert apply_rest_link(s, linked) == linked


@pytest.mark.parametrize("seed", SEEDS)
def test_reconcile_result_is_valid_and_a_subset(seed):
    rng, g, s = world(seed)
    new = text_edit(rng, g)
    out = reconcile(s, g, new)
    assert isinstance(validate(score_to_json(out), new), Valid)
    old_notes = {(n.t, n.d, n.a) for n in s.all_notes()}
    assert all((n.t, n.d, n.a) in old_notes for n in out.all_notes())
    assert len(out.all_notes()) <= len(s.all_notes())
    assert (out.pickup, out.meters, out.tempos, out.snap) == (s.pickup, s.meters, s.tempos, s.snap)
    # then the rest link keeps it valid
    assert isinstance(validate(score_to_json(out), apply_rest_link(out, new)), Valid)


@pytest.mark.parametrize("seed", SEEDS)
def test_reconcile_identity(seed):
    _, g, s = world(seed)
    assert reconcile(s, g, g) == s


@pytest.mark.parametrize("seed", SEEDS)
def test_grid_rest_edit_keeps_validity_and_order(seed):
    rng, g, s = world(seed)
    new = rest_edit(rng, g)
    try:
        result = apply_grid_rest_edit(s, g, new)
    except ScoreInvalid as error:  # only an overflow may refuse a rest edit
        assert error.reason.value == "exceeds_max_ticks"
        return
    assert isinstance(validate(score_to_json(result.score), result.grid), Valid)
    assert is_rest_only_edit(new, result.grid)
    assert apply_rest_link(result.score, result.grid) == result.grid
    for old_line, new_line in zip(s.lines, result.score.lines, strict=True):
        assert [n.s for n in old_line.notes] == [n.s for n in new_line.notes]
        assert [(n.d, n.a) for n in old_line.notes] == [(n.d, n.a) for n in new_line.notes]


@pytest.mark.parametrize("seed", SEEDS)
def test_grid_rest_edit_of_a_linked_grid_is_identity(seed):
    _, g, s = world(seed)
    linked = apply_rest_link(s, g)
    result = apply_grid_rest_edit(s, linked, linked)
    assert result.score == s and result.grid == linked


@pytest.mark.parametrize("seed", range(100))
def test_text_edits_are_not_rest_only_or_change_nothing(seed):
    rng, g, s = world(seed)
    new = text_edit(rng, g)
    if not is_rest_only_edit(g, new):
        with pytest.raises(NotRestOnlyEdit):
            apply_grid_rest_edit(s, g, new)


@pytest.mark.parametrize("seed", SEEDS)
def test_draft_round_trips_and_is_valid(seed):
    rng = Lcg(seed + 99)
    g = random_grid(rng)
    num, den = rng.pick(((4, 4), (3, 4), (6, 8), (7, 8), (2, 2), (5, 16), (12, 8)))
    s = draft_from_grid(g, {"num": num, "den": den}, 120, rng.pick(("1/8", "1/16", "1/32", "1/8t", "1/16t")))
    assert apply_rest_link(s, g) == g
    for line, grid_line in zip(s.lines, grid_lines(g), strict=True):
        first = line_cells(grid_line)[:1]
        if line.notes and first and first[0].get("rest") is not True:
            assert bar_at_tick(s, line.notes[0].t).offset == 0  # a line that opens with a syllable opens a bar


@pytest.mark.parametrize("seed", range(100))
def test_bar_at_tick_and_bar_start_tick_agree(seed):
    rng = Lcg(seed + 5)
    s = check_score(random_score(rng, None), None)
    for bar in range(0, 40):
        start = bar_start_tick(s, bar)
        assert bar_at_tick(s, start).bar == bar and bar_at_tick(s, start).offset == 0
