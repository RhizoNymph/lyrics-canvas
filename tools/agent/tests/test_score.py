"""Scores in lcx: the vendored reference (current, passes the shared vectors), the cadence rendering,
the reads that show it, and the set_score plan op (parse, check, apply)."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import pytest
from conftest import AGENT_DIR, ALBUM, DRAFTS, S1, Env

from lcxlib.cadence import cadence
from lcxlib.score_ref import (
    ScoreTextMismatch, apply_rest_link, bar_at_tick, beat_unit, changed_line_texts, check_score, splice_line_texts,
    validate,
)

sys.path.insert(0, str(AGENT_DIR / "scripts"))
import vendor_score_ref  # noqa: E402

VECTORS = json.loads((AGENT_DIR.parent.parent / "spec" / "score_vectors.json").read_text(encoding="utf-8"))

SONG = "aaaaaaaa-0000-4000-8000-000000000901"


def cell(syllable: str, word_index: int, stress: int = 1) -> dict[str, Any]:
    return {"syllable": syllable, "stress": stress, "word_index": word_index}


REST = {"rest": True}
GRID = {"lines": [
    {"cells": [cell("i", 0), cell("go", 1), cell("now", 2)], "syllable_count": 3, "raw_text": "I go now"},
    {"cells": [cell("hel", 0, 0), cell("lo", 0), cell("world", 1)], "syllable_count": 3, "raw_text": "hello world"},
]}


def score(*lines: list[tuple[int, int, int, int]], **top: Any) -> dict[str, Any]:
    body = {"v": 1, "ppq": 48, "pickup": 0, "meters": [{"bar": 0, "num": 4, "den": 4}],
            "tempos": [{"bar": 0, "bpm": 90}], "snap": "1/16",
            "lines": [{"notes": [{"s": s, "t": t, "d": d, "a": a} for s, t, d, a in line]} for line in lines]}
    body.update(top)
    return body


EVEN = score([(0, 0, 24, 1), (1, 24, 24, 1), (2, 48, 24, 1)], [(0, 192, 24, 0), (1, 216, 24, 1), (2, 240, 24, 1)])
GAP = score([(0, 0, 24, 1), (1, 72, 24, 1), (2, 96, 24, 1)], [(0, 192, 24, 0), (1, 216, 24, 1), (2, 240, 24, 1)])


@pytest.fixture
def scored(lc: Env) -> Env:
    lc.backend.add_snippet(SONG, "I go now\nhello world", [DRAFTS], version=4, ranks=["a5"], grid=json.loads(json.dumps(GRID)),
                           score=json.loads(json.dumps(EVEN)), score_view=True)
    return lc


def check(lc: Env, *ops: dict[str, Any]) -> dict[str, Any]:
    r = lc.run("plan", "check", lc.plan_file(list(ops)), "--json")
    assert r.code in (0, 1), r.err
    return r.json()["check"]


def codes(data: dict[str, Any]) -> list[tuple[int, str]]:
    return [(i["index"], i["code"]) for i in data["issues"]]


# ---- the vendored reference ----


def test_vendored_reference_is_current() -> None:
    target = AGENT_DIR / "lcxlib" / "score_ref"
    rendered = vendor_score_ref.render()
    assert sorted(p.name for p in target.glob("*.py")) == sorted(rendered)
    for name, content in rendered.items():
        assert (target / name).read_text(encoding="utf-8") == content, (
            f"lcxlib/score_ref/{name} is out of date: run python tools/agent/scripts/vendor_score_ref.py")


def test_vendored_reference_imports_no_third_party_package() -> None:
    for path in (AGENT_DIR / "lcxlib" / "score_ref").glob("*.py"):
        text = path.read_text(encoding="utf-8")
        assert "import pydantic" not in text and "from pydantic" not in text and "app.services" not in text, path.name


@pytest.mark.parametrize("case", VECTORS["validate"], ids=lambda c: c["name"])
def test_validate_vectors(case: dict[str, Any]) -> None:
    result = validate(case["score"], case["grid"])
    expect = case["expect"]
    if expect["ok"]:
        assert not hasattr(result, "error"), result
    else:
        error = result.error
        got = {"ok": False, "reason": error.reason.value,
               **({"line": error.line} if error.line is not None else {}),
               **({"note": error.note} if error.note is not None else {})}
        assert got == expect


@pytest.mark.parametrize("case", VECTORS["rest_link"], ids=lambda c: c["name"])
def test_rest_link_vectors(case: dict[str, Any]) -> None:
    if "grid" not in case["expect"]:
        return
    typed = check_score(case["score"], case["grid"])
    assert apply_rest_link(typed, case["grid"]) == case["expect"]["grid"]


@pytest.mark.parametrize("case", VECTORS["text_splice"], ids=lambda c: c["name"])
def test_text_splice_vectors(case: dict[str, Any]) -> None:
    replacements = {int(k): v for k, v in case["replacements"].items()}
    if "text" in case["expect"]:
        assert splice_line_texts(case["raw_text"], replacements) == case["expect"]["text"]
    else:
        with pytest.raises(ScoreTextMismatch):
            splice_line_texts(case["raw_text"], replacements)


@pytest.mark.parametrize("case", VECTORS["bar_at_tick"], ids=lambda c: c["name"])
def test_bar_at_tick_vectors(case: dict[str, Any]) -> None:
    typed = check_score(case["score"], {"lines": [{"cells": []}] * len(case["score"]["lines"])})
    for q in case["queries"]:
        pos = bar_at_tick(typed, q["tick"])
        assert (pos.bar, pos.offset) == (q["bar"], q["offset"])


def test_changed_line_texts_is_exported() -> None:
    assert changed_line_texts(GRID, GRID) == {}
    assert beat_unit(check_score(EVEN, GRID), 0) == 48


# ---- cadence ----


def test_cadence_none_without_score() -> None:
    assert cadence(None, GRID) is None


def test_cadence_even_eighths() -> None:
    assert cadence(EVEN, GRID) == [
        "4/4, 90 bpm, snap 1/16",
        '1. "I go now": bar 1: i@1~0.5> go@1.5~0.5> now@2~0.5>',
        '2. "hello world": bar 2: hel@1~0.5 lo@1.5~0.5> world@2~0.5>',
    ]


def test_cadence_pickup_triplets_changes_and_unplaced() -> None:
    sc = score([(0, 0, 24, 2), (1, 24, 16, 0), (2, 40, 16, 0)], [(0, 216, 24, 0), (2, 408, 144, 1)], pickup=24,
               meters=[{"bar": 0, "num": 4, "den": 4}, {"bar": 2, "num": 6, "den": 8}],
               tempos=[{"bar": 0, "bpm": 90}, {"bar": 1, "bpm": 120.5}])
    assert cadence(sc, GRID) == [
        "4/4, 90 bpm, pickup 0.5 beat, snap 1/16; bar 2: 120.5 bpm; bar 3: 6/8",
        '1. "I go now": pickup: i@4.5~0.5>> | bar 1: go@1~1/3 now@1+1/3~1/3',
        '2. "hello world": bar 2: hel@1~0.5 | bar 3: world@1~6>  (unplaced: lo)',
    ]


def test_cadence_rests_only_and_empty_lines() -> None:
    grid = {"lines": [{"cells": [REST, REST], "raw_text": "_ _"}, {"cells": [cell("go", 0)], "raw_text": "go"}]}
    assert cadence(score([], []), grid)[1:] == ['1. "_ _": (rests only)', '2. "go": (no notes)']


def test_cadence_of_a_score_that_does_not_fit() -> None:
    assert cadence(score([]), GRID) == ["(score does not fit the grid: line_count_mismatch)"]


# ---- reads ----


def test_canvas_read_shows_cadence_and_view(scored: Env) -> None:
    r = scored.run("canvas", "Drafts")
    assert r.code == 0, r.err
    by_id = {s["id"]: s for s in r.json()["snippets"]}
    assert by_id[SONG]["cadence"] == cadence(EVEN, GRID) and by_id[SONG]["score_view"] is True
    assert by_id[S1]["cadence"] is None and by_id[S1]["score_view"] is False
    assert "score" not in by_id[SONG]


def test_dump_adds_cadence_and_keeps_the_raw_score(scored: Env) -> None:
    snap = scored.run("dump").json()
    s = next(x for x in snap["snippets"] if x["id"] == SONG)
    assert s["score"] == EVEN and s["cadence"] == cadence(EVEN, GRID)
    assert next(x for x in snap["snippets"] if x["id"] == S1)["cadence"] is None


def test_score_read(scored: Env) -> None:
    r = scored.run("score", SONG, "--pretty")
    assert r.code == 0, r.err
    data = r.json()
    assert data["score"] == EVEN and data["version"] == 4 and data["score_view"] is True
    assert data["lines"] == [{"text": "I go now", "syllables": ["i", "go", "now"]},
                             {"text": "hello world", "syllables": ["hel", "lo", "world"]}]
    assert data["cadence"] == cadence(EVEN, GRID)


def test_score_read_unknown_snippet_is_usage_error(scored: Env) -> None:
    r = scored.run("score", "00000000-0000-0000-0000-00000000dead")
    assert r.code == 2 and "no snippet" in r.err


# ---- plan parse ----


@pytest.mark.parametrize(
    ("op", "message"),
    [
        ({"op": "set_score", "snippet_id": SONG}, "missing field 'score'"),
        ({"op": "set_score", "snippet_id": SONG, "score": [1]}, "score must be object|null"),
        ({"op": "set_score", "snippet_id": SONG, "score": None, "score_view": "yes"}, "score_view must be bool"),
        ({"op": "set_score", "snippet_id": SONG, "score": None, "base_version": 3}, "unknown field 'base_version'"),
    ],
)
def test_set_score_parse_errors(scored: Env, op: dict[str, Any], message: str) -> None:
    r = scored.run("plan", "check", scored.plan_file([op]))
    assert r.code == 1 and message in r.err


# ---- plan check ----


def test_set_score_check_shows_cadence_text_and_writes_nothing(scored: Env) -> None:
    data = check(scored, {"op": "set_score", "snippet_id": SONG, "score": GAP})
    assert data["ok"], data["issues"]
    (step,) = data["steps"]
    assert step["change"] and step["summary"].startswith("set the score of snippet aaaaaaaa")
    assert '- 1. "I go now": bar 1: i@1~0.5> go@1.5~0.5> now@2~0.5>' in step["details"]
    assert '+ 1. "i _ go now": bar 1: i@1~0.5> go@2.5~0.5> now@3~0.5>' in step["details"]
    assert "  + i _ go now" in step["details"] and "  - I go now" in step["details"]
    assert scored.backend.writes() == []


def test_set_score_check_human_output(scored: Env) -> None:
    r = scored.run("plan", "check", scored.plan_file([{"op": "set_score", "snippet_id": SONG, "score": GAP, "score_view": False}]))
    assert r.code == 0
    assert "  1. * set the score of snippet aaaaaaaa" in r.out
    assert "view: staff -> grid" in r.out


def test_set_score_invalid_is_a_problem_with_reason(scored: Env) -> None:
    bad = score([(0, 0, 24, 0), (1, 12, 24, 0)], [])
    data = check(scored, {"op": "set_score", "snippet_id": SONG, "score": bad})
    assert codes(data) == [(1, "invalid_score")]
    assert "notes_overlap, line 0, note 1" in data["issues"][0]["message"]


def test_set_score_text_mismatch_keeps_the_text(lc: Env) -> None:
    lc.backend.add_snippet(SONG, "I go now", [DRAFTS], grid=json.loads(json.dumps(GRID)))
    data = check(lc, {"op": "set_score", "snippet_id": SONG, "score": score([], [(1, 0, 24, 0), (2, 96, 24, 0)])})
    assert data["ok"], data["issues"]
    assert not any(d.startswith("text") for d in data["steps"][0]["details"])


def test_set_score_wrong_line_count(scored: Env) -> None:
    data = check(scored, {"op": "set_score", "snippet_id": SONG, "score": score([])})
    assert codes(data) == [(1, "invalid_score")] and "line_count_mismatch" in data["issues"][0]["message"]


def test_set_score_same_score_is_no_change(scored: Env) -> None:
    raw = json.loads(json.dumps(EVEN))
    raw["lines"][0]["notes"][0]["d"] = 24.0  # the same score as JSON values
    data = check(scored, {"op": "set_score", "snippet_id": SONG, "score": raw})
    assert data["ok"] and data["steps"][0]["change"] is False


def test_set_score_view_only_is_a_change(scored: Env) -> None:
    data = check(scored, {"op": "set_score", "snippet_id": SONG, "score": EVEN, "score_view": False})
    step = data["steps"][0]
    assert step["change"] and step["details"] == ["view: staff -> grid"]


def test_remove_score(scored: Env) -> None:
    data = check(scored, {"op": "set_score", "snippet_id": SONG, "score": None})
    step = data["steps"][0]
    assert step["change"] and step["summary"].startswith("remove the score")
    assert "+ (no score)" in step["details"] and "view: staff -> grid" in step["details"]


def test_remove_missing_score_is_no_change(scored: Env) -> None:
    data = check(scored, {"op": "set_score", "snippet_id": S1, "score": None, "score_view": True})
    assert data["ok"] and data["steps"][0]["change"] is False and data["steps"][0]["no_change"] == "it has no score"


def test_score_on_a_snippet_without_grid_must_have_no_lines(scored: Env) -> None:
    assert check(scored, {"op": "set_score", "snippet_id": S1, "score": score()})["ok"]
    data = check(scored, {"op": "set_score", "snippet_id": S1, "score": score([])})
    assert codes(data) == [(1, "invalid_score")]


def test_set_score_and_edit_text_on_one_snippet_is_refused(scored: Env) -> None:
    data = check(
        scored,
        {"op": "edit_text", "snippet_id": SONG, "raw_text": "I go", "base_version": 4},
        {"op": "set_score", "snippet_id": SONG, "score": None},
    )
    assert codes(data) == [(2, "invalid_field")] and "earlier edit_text" in data["issues"][0]["message"]
    data = check(
        scored,
        {"op": "set_score", "snippet_id": SONG, "score": GAP},
        {"op": "set_score", "snippet_id": SONG, "score": None},
    )
    assert codes(data) == [(2, "invalid_field")]


def test_edit_text_of_a_scored_snippet_warns_about_reconcile(scored: Env) -> None:
    data = check(scored, {"op": "edit_text", "snippet_id": SONG, "raw_text": "I go now\nhello there", "base_version": 4})
    assert any("it has a score" in d for d in data["steps"][0]["details"])


def test_set_score_unknown_snippet(scored: Env) -> None:
    data = check(scored, {"op": "set_score", "snippet_id": "00000000-0000-0000-0000-00000000dead", "score": None})
    assert codes(data) == [(1, "unknown_snippet")]


# ---- plan apply ----


def test_set_score_apply_puts_score_view_and_base_version(scored: Env) -> None:
    ops = [{"op": "set_score", "snippet_id": SONG, "score": GAP, "score_view": False}]
    r = scored.run("plan", "apply", scored.plan_file(ops), "--yes", "--json")
    assert r.code == 0, r.out + r.err
    (put,) = scored.backend.writes()
    assert (put.method, put.path, put.query) == ("PUT", f"/api/snippets/{SONG}", {"canvas_id": DRAFTS})
    assert put.body == {"score": GAP, "score_view": False, "base_version": 4}
    stored = scored.backend.snippets[SONG]
    assert stored["score"] == GAP and stored["score_view"] is False and stored["version"] == 5
    assert stored["raw_text"] == "i _ go now\nhello world"
    assert r.json()["apply"]["done"][0]["result"] == {"version": 5, "score_view": False}


def test_set_score_apply_without_view_sends_no_view(scored: Env) -> None:
    r = scored.run("plan", "apply", scored.plan_file([{"op": "set_score", "snippet_id": SONG, "score": None}]), "--yes")
    assert r.code == 0, r.out + r.err
    (put,) = scored.backend.writes()
    assert put.body == {"score": None, "base_version": 4}
    assert scored.backend.snippets[SONG]["score"] is None and scored.backend.snippets[SONG]["score_view"] is False


def test_set_score_apply_after_a_tag_change_uses_the_new_version(scored: Env) -> None:
    ops = [{"op": "create_tag", "name": "cadence"}, {"op": "tag_snippet", "snippet_id": SONG, "add": ["cadence"]},
           {"op": "set_score", "snippet_id": SONG, "score": GAP}]
    r = scored.run("plan", "apply", scored.plan_file(ops), "--yes")
    assert r.code == 0, r.out + r.err
    assert scored.backend.writes()[-1].body["base_version"] == 5


def test_set_score_apply_stops_on_server_refusal(scored: Env) -> None:
    scored.backend.fail_next("PUT", f"/api/snippets/{SONG}", 422, {"error": "invalid_score", "reason": "notes_overlap"})
    r = scored.run("plan", "apply", scored.plan_file([{"op": "set_score", "snippet_id": SONG, "score": GAP}]), "--yes")
    assert r.code == 4
    assert "FAILED at op 1" in r.out and "422 invalid_score" in r.out


def test_set_score_on_album_uses_a_canvas_holding_the_snippet(scored: Env) -> None:
    scored.backend.add_snippet("bbbbbbbb-0000-4000-8000-000000000902", "go", [ALBUM], grid={"lines": [{"cells": [cell("go", 0)], "raw_text": "go"}]})
    ops = [{"op": "set_score", "snippet_id": "bbbbbbbb-0000-4000-8000-000000000902", "score": score([(0, 0, 48, 0)])}]
    r = scored.run("plan", "apply", scored.plan_file(ops), "--yes")
    assert r.code == 0, r.out + r.err
    assert scored.backend.writes()[0].query == {"canvas_id": ALBUM}


def test_vectors_file_is_where_the_tests_expect() -> None:
    assert Path(AGENT_DIR.parent.parent / "spec" / "score_vectors.json").is_file()
    assert VECTORS["version"] == 1
