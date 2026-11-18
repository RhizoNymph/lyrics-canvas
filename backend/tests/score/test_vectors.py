"""The shared score vectors (spec/score_vectors.json): generated from the Python reference by
scripts/gen_score_vectors.py; the web and Android ports read the same file."""

from __future__ import annotations

import json
import math

import pytest

from app.services.score import (
    MAX_BAR,
    MAX_TICKS,
    PPQ,
    WHOLE,
    NotRestOnlyEdit,
    ScoreInvalid,
    ScoreTextMismatch,
    apply_grid_rest_edit,
    apply_rest_link,
    bar_at_tick,
    bar_count,
    bar_length,
    bar_start_tick,
    beat_unit,
    check_score,
    draft_from_grid,
    end_tick,
    is_rest_only_edit,
    lcs_pairs,
    line_key,
    metric_weight,
    normalize_word,
    reconcile,
    score_to_json,
    splice_line_texts,
    tick_to_ms,
    validate,
    word_key,
)
from app.services.score.validate import Invalid
from scripts.gen_score_vectors import VECTORS_PATH, render_vectors

REGENERATE = "re-run `python -m scripts.gen_score_vectors` from backend/ and commit spec/score_vectors.json"
TEXT = VECTORS_PATH.read_text(encoding="utf-8")
VECTORS = json.loads(TEXT)
SECTIONS = {
    "validate", "bars", "bar_at_tick", "beat_unit", "metric_weight", "tick_to_ms", "normalize_word", "lcs",
    "line_key", "text_splice", "rest_link", "rest_only_edit", "grid_rest_edit", "reconcile", "draft_from_grid",
}


def cases(section):
    return pytest.mark.parametrize("case", VECTORS[section], ids=lambda c: c["name"])


def test_file_shape_and_size():
    assert VECTORS["version"] == 1
    assert set(VECTORS) == SECTIONS | {"version", "constants"}
    assert len(TEXT.encode("utf-8")) < 1_500_000
    for section in SECTIONS - {"text_splice", "line_key", "normalize_word"}:
        assert len(VECTORS[section]) >= 30, section


def test_constants():
    c = VECTORS["constants"]
    assert (c["ppq"], c["whole"], c["max_bar"], c["max_ticks"]) == (PPQ, WHOLE, MAX_BAR, MAX_TICKS)
    assert c["snap_ticks"] == {"1/8": 24, "1/16": 12, "1/32": 6, "1/8t": 16, "1/16t": 8}


def test_file_is_up_to_date():
    assert TEXT == render_vectors(), REGENERATE


def test_every_reason_is_covered():
    reasons = {c["expect"].get("reason") for c in VECTORS["validate"]} - {None}
    assert len(reasons) == 24


@cases("validate")
def test_validate(case):
    result = validate(case["score"], case["grid"])
    if case["expect"]["ok"]:
        assert not isinstance(result, Invalid), result
    else:
        assert isinstance(result, Invalid)
        got = {"ok": False, "reason": result.error.reason.value}
        if result.error.line is not None:
            got["line"] = result.error.line
        if result.error.note is not None:
            got["note"] = result.error.note
        assert got == case["expect"]


@cases("bars")
def test_bars(case):
    s = check_score(case["score"], case["grid"])
    for bar in case["bars"]:
        assert (bar_start_tick(s, bar["bar"]), bar_length(s, bar["bar"])) == (bar["start"], bar["length"])
    assert (end_tick(s), bar_count(s)) == (case["end_tick"], case["bar_count"])


@cases("bar_at_tick")
def test_bar_at_tick(case):
    s = check_score(case["score"], None)
    for q in case["queries"]:
        position = bar_at_tick(s, q["tick"])
        assert (position.bar, position.offset) == (q["bar"], q["offset"]), q


@cases("beat_unit")
def test_beat_unit(case):
    s = check_score(case["score"], None)
    assert [beat_unit(s, q["tick"]) for q in case["queries"]] == [q["beat_unit"] for q in case["queries"]]


@cases("metric_weight")
def test_metric_weight(case):
    s = check_score(case["score"], None)
    assert [metric_weight(s, q["tick"]) for q in case["queries"]] == [q["weight"] for q in case["queries"]]


@cases("tick_to_ms")
def test_tick_to_ms(case):
    s = check_score(case["score"], None)
    for q in case["queries"]:
        assert math.isclose(tick_to_ms(s, q["tick"]), q["ms"], rel_tol=0, abs_tol=1e-6), q


@cases("normalize_word")
def test_normalize_word(case):
    assert (normalize_word(case["text"]), word_key(case["text"])) == (case["normalized"], case["key"])


@cases("lcs")
def test_lcs(case):
    assert [list(p) for p in lcs_pairs(case["a"], case["b"])] == case["pairs"]


@cases("line_key")
def test_line_key(case):
    assert line_key(case["line"]) == case["key"]


@cases("text_splice")
def test_text_splice(case):
    replacements = {int(k): v for k, v in case["replacements"].items()}
    if "error" in case["expect"]:
        with pytest.raises(ScoreTextMismatch):
            splice_line_texts(case["raw_text"], replacements)
    else:
        assert splice_line_texts(case["raw_text"], replacements) == case["expect"]["text"]


def _loose(raw):
    """Typed without a grid check (for precondition-failure cases)."""
    lines = len(raw["lines"])
    return check_score(raw, {"lines": [{"cells": [{"syllable": "x"}] * 64}] * lines})


def _invalid(case_expect, call):
    with pytest.raises(ScoreInvalid) as info:
        call()
    assert info.value.reason.value == case_expect["reason"]


@cases("rest_link")
def test_rest_link(case):
    s = _loose(case["score"])
    if case["expect"].get("error"):
        _invalid(case["expect"], lambda: apply_rest_link(s, case["grid"]))
    else:
        assert apply_rest_link(s, case["grid"]) == case["expect"]["grid"]


@cases("rest_only_edit")
def test_rest_only_edit(case):
    assert is_rest_only_edit(case["old_grid"], case["new_grid"]) is case["expect"]


@cases("grid_rest_edit")
def test_grid_rest_edit(case):
    s = _loose(case["score"])
    expect = case["expect"]
    match expect.get("error"):
        case "not_rest_only":
            with pytest.raises(NotRestOnlyEdit):
                apply_grid_rest_edit(s, case["old_grid"], case["new_grid"])
        case "invalid_score":
            _invalid(expect, lambda: apply_grid_rest_edit(s, case["old_grid"], case["new_grid"]))
        case _:
            result = apply_grid_rest_edit(s, case["old_grid"], case["new_grid"])
            assert score_to_json(result.score) == expect["score"]
            assert result.grid == expect["grid"]


@cases("reconcile")
def test_reconcile(case):
    s = _loose(case["score"])
    if case["expect"].get("error"):
        _invalid(case["expect"], lambda: reconcile(s, case["old_grid"], case["new_grid"]))
    else:
        assert score_to_json(reconcile(s, case["old_grid"], case["new_grid"])) == case["expect"]["score"]


@cases("draft_from_grid")
def test_draft_from_grid(case):
    call = lambda: draft_from_grid(case["grid"], case["meter"], case["bpm"], case["note"])  # noqa: E731
    if case["expect"].get("error"):
        _invalid(case["expect"], call)
    else:
        assert score_to_json(call()) == case["expect"]["score"]
        if case["grid"] is not None:
            assert apply_rest_link(call(), case["grid"]) == case["grid"]
