"""Write spec/score_vectors.json from the Python reference (app/services/score/).

Run from backend/:  python -m scripts.gen_score_vectors
The file is the contract the web (frontend/src/lib/score/) and Android (core/logic/.../score/) ports
must pass; tests/score/test_vectors.py fails whenever it drifts from the reference.
Each case is one line of JSON so diffs stay readable.
"""

from __future__ import annotations

import copy
import json
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any

from app.services.score import (
    MAX_BAR,
    MAX_TICKS,
    PPQ,
    SNAP_TICKS,
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
from scripts.score_vector_random import Lcg, finish_line, random_grid, random_line, random_score, rest_edit, text_edit

VECTORS_PATH = Path(__file__).resolve().parents[2] / "spec" / "score_vectors.json"
SEED = 20261117

Case = dict[str, Any]


# ---- builders -------------------------------------------------------------------------------


def grid(*texts: str) -> dict[str, Any]:
    return {"lines": [finish_line(_line_cells(t)) for t in texts]}


def _line_cells(text: str) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    index = 0
    for token in text.replace("_", " _ ").split():
        if token == "_":
            out.append({"rest": True})
            continue
        parts = token.split("-")
        word = "".join(p.lstrip("^") for p in parts)
        out.extend({"syllable": p.lstrip("^"), "stress": 1 if p.startswith("^") else 0, "word_index": index, "word": word} for p in parts)
        index += 1
    return out


def score(
    lines: list[list[tuple[int, int, int, int]]],
    meters: list[tuple[int, int, int]] | None = None,
    tempos: list[tuple[int, int | float]] | None = None,
    pickup: int = 0,
    snap: str = "1/16",
) -> dict[str, Any]:
    return {
        "v": 1,
        "ppq": 48,
        "pickup": pickup,
        "meters": [{"bar": b, "num": n, "den": d} for b, n, d in (meters or [(0, 4, 4)])],
        "tempos": [{"bar": b, "bpm": bpm} for b, bpm in (tempos or [(0, 90)])],
        "snap": snap,
        "lines": [{"notes": [{"s": s, "t": t, "d": d, "a": a} for s, t, d, a in notes]} for notes in lines],
    }


def timing_only(meters, tempos=None, pickup=0) -> dict[str, Any]:
    return score([], meters=meters, tempos=tempos, pickup=pickup)


def _error(error: ScoreInvalid) -> dict[str, Any]:
    return {"error": "invalid_score", "reason": error.reason.value}


# ---- validate -------------------------------------------------------------------------------

G2 = grid("I go")
OK = score([[(0, 0, 12, 1), (1, 12, 12, 0)]])


def _with(raw: Any, **changes: Any) -> Any:
    out = copy.deepcopy(raw)
    out.update(changes)
    return out


def _note(raw: dict[str, Any], **changes: Any) -> dict[str, Any]:
    return _with(OK, lines=[{"notes": [{"s": 0, "t": 0, "d": 12, "a": 1, **changes}]}])


def _without(raw: dict[str, Any], key: str) -> dict[str, Any]:
    out = copy.deepcopy(raw)
    del out[key]
    return out


def validate_cases(rng: Lcg) -> list[Case]:
    m = lambda *entries: [{"bar": b, "num": n, "den": d} for b, n, d in entries]  # noqa: E731
    t = lambda *entries: [{"bar": b, "bpm": bpm} for b, bpm in entries]  # noqa: E731
    hand: list[tuple[str, Any, Any]] = [
        ("ok", OK, G2),
        ("ok empty lines", _with(OK, lines=[{"notes": []}]), G2),
        ("ok null grid", _with(OK, lines=[]), None),
        ("ok extra note keys", _note(OK, p=60, meta={"x": [1, None]}), G2),
        ("ok integral floats", _with(OK, pickup=0.0, lines=[{"notes": [{"s": 0.0, "t": 12.0, "d": 6.0, "a": 2.0}]}]), G2),
        ("ok limits", _with(OK, meters=m(*[(i, 4, 4) for i in range(255)], (2048, 32, 32)), tempos=t((0, 20), (1, 400), (2048, 90.15))), G2),
        ("ok touching notes across lines", score([[(0, 0, 12, 0), (1, 12, 24, 0)], [(0, 36, 12, 0)]]), grid("I go", "now")),
        ("ok note ends at max ticks", _note(OK, t=MAX_TICKS - 1, d=1), G2),
        ("ok pickup max", _with(OK, meters=m((0, 3, 8)), pickup=71), G2),
        ("ok rest-only line", score([[], [(0, 0, 1, 0)]]), grid("_ _", "go")),
        ("ok bpm decimals", _with(OK, tempos=t((0, 33.33))), G2),
        ("ok all dens", _with(OK, meters=m((0, 1, 1), (1, 1, 2), (2, 1, 4), (3, 1, 8), (4, 1, 16), (5, 1, 32))), G2),
        ("not an object: null", None, G2),
        ("not an object: array", [], G2),
        ("not an object: string", "score", G2),
        ("missing key", _without(OK, "snap"), G2),
        ("unknown key", _with(OK, extra=1), G2),
        ("v string", _with(OK, v="1"), G2),
        ("v boolean", _with(OK, v=True), G2),
        ("v 2", _with(OK, v=2), G2),
        ("v 1.5", _with(OK, v=1.5), G2),
        ("ppq 96", _with(OK, ppq=96), G2),
        ("ppq null", _with(OK, ppq=None), G2),
        ("version before ppq", _with(OK, v=2, ppq=1), G2),
        ("meters object", _with(OK, meters={}), G2),
        ("meters empty", _with(OK, meters=[]), G2),
        ("meters 257", _with(OK, meters=m(*[(i, 4, 4) for i in range(257)])), G2),
        ("meter missing den", _with(OK, meters=[{"bar": 0, "num": 4}]), G2),
        ("meter unknown key", _with(OK, meters=[{"bar": 0, "num": 4, "den": 4, "x": 1}]), G2),
        ("meter num float", _with(OK, meters=[{"bar": 0, "num": 4.5, "den": 4}]), G2),
        ("meter first at bar 1", _with(OK, meters=m((1, 4, 4))), G2),
        ("meter first at bar -1", _with(OK, meters=m((-1, 4, 4))), G2),
        ("meters same bar", _with(OK, meters=m((0, 4, 4), (0, 3, 4))), G2),
        ("meters decreasing", _with(OK, meters=m((0, 4, 4), (3, 3, 4), (2, 3, 4))), G2),
        ("meter bar 2049", _with(OK, meters=m((0, 4, 4), (2049, 3, 4))), G2),
        ("meter num 0", _with(OK, meters=m((0, 0, 4))), G2),
        ("meter num 33", _with(OK, meters=m((0, 33, 4))), G2),
        ("meter den 3", _with(OK, meters=m((0, 4, 3))), G2),
        ("meter den 64", _with(OK, meters=m((0, 4, 64))), G2),
        ("meter order before value", _with(OK, meters=m((0, 4, 4), (0, 0, 3))), G2),
        ("tempos null", _with(OK, tempos=None), G2),
        ("tempos empty", _with(OK, tempos=[]), G2),
        ("tempos 257", _with(OK, tempos=t(*[(i, 90) for i in range(257)])), G2),
        ("tempo bpm string", _with(OK, tempos=[{"bar": 0, "bpm": "90"}]), G2),
        ("tempo bpm boolean", _with(OK, tempos=[{"bar": 0, "bpm": True}]), G2),
        ("tempo bar float", _with(OK, tempos=[{"bar": 0.5, "bpm": 90}]), G2),
        ("tempo unknown key", _with(OK, tempos=[{"bar": 0, "bpm": 90, "x": 0}]), G2),
        ("tempo first at bar 2", _with(OK, tempos=t((2, 90))), G2),
        ("tempos same bar", _with(OK, tempos=t((0, 90), (0, 91))), G2),
        ("tempo bar 2049", _with(OK, tempos=t((0, 90), (2049, 91))), G2),
        ("tempo 19.99", _with(OK, tempos=t((0, 19.99))), G2),
        ("tempo 400.01", _with(OK, tempos=t((0, 400.01))), G2),
        ("tempo 3 decimals", _with(OK, tempos=t((0, 90.155))), G2),
        ("tempo 120.125", _with(OK, tempos=t((0, 120.125))), G2),
        ("meters before tempos", _with(OK, meters=[], tempos=[]), G2),
        ("pickup negative", _with(OK, pickup=-1), G2),
        ("pickup a full bar", _with(OK, pickup=192), G2),
        ("pickup a full 3/8 bar", _with(OK, meters=m((0, 3, 8)), pickup=72), G2),
        ("pickup string", _with(OK, pickup="0"), G2),
        ("pickup before snap", _with(OK, pickup=-1, snap="x"), G2),
        ("snap 1/4", _with(OK, snap="1/4"), G2),
        ("snap null", _with(OK, snap=None), G2),
        ("snap upper case", _with(OK, snap="1/16T"), G2),
        ("snap number", _with(OK, snap=16), G2),
        ("snap before lines", _with(OK, snap="x", lines=[]), G2),
        ("lines object", _with(OK, lines={}), G2),
        ("lines too few", _with(OK, lines=[]), G2),
        ("lines too many", _with(OK, lines=[{"notes": []}, {"notes": []}]), G2),
        ("line not an object", _with(OK, lines=[[]]), G2),
        ("line notes object", _with(OK, lines=[{"notes": {}}]), G2),
        ("line unknown key", _with(OK, lines=[{"notes": [], "x": 1}]), G2),
        ("note missing a", _with(OK, lines=[{"notes": [{"s": 0, "t": 0, "d": 1}]}]), G2),
        ("note a float", _note(OK, a=0.5), G2),
        ("note t boolean", _note(OK, t=True), G2),
        ("note t string", _note(OK, t="0"), G2),
        ("note t null", _note(OK, t=None), G2),
        ("note t 1e20", _note(OK, t=1e20), G2),
        ("note t 2^53", _note(OK, t=2**53), G2),
        ("note t 2^53-1", _note(OK, t=2**53 - 1), G2),
        ("second note not an object", _with(OK, lines=[{"notes": [{"s": 0, "t": 0, "d": 1, "a": 0}, 5]}]), G2),
        ("s out of range", _note(OK, s=2), G2),
        ("s negative", _note(OK, s=-1), G2),
        ("s repeated", _with(OK, lines=[{"notes": [{"s": 1, "t": 0, "d": 1, "a": 0}, {"s": 1, "t": 5, "d": 1, "a": 0}]}]), G2),
        ("s decreasing", _with(OK, lines=[{"notes": [{"s": 1, "t": 0, "d": 1, "a": 0}, {"s": 0, "t": 5, "d": 1, "a": 0}]}]), G2),
        ("t negative", _note(OK, t=-1), G2),
        ("d zero", _note(OK, d=0), G2),
        ("d negative", _note(OK, d=-12), G2),
        ("a 3", _note(OK, a=3), G2),
        ("a -1", _note(OK, a=-1), G2),
        ("past max ticks", _note(OK, t=MAX_TICKS, d=1), G2),
        ("overlap", _with(OK, lines=[{"notes": [{"s": 0, "t": 0, "d": 24, "a": 0}, {"s": 1, "t": 12, "d": 12, "a": 0}]}]), G2),
        ("out of time order", _with(OK, lines=[{"notes": [{"s": 0, "t": 20, "d": 4, "a": 0}, {"s": 1, "t": 0, "d": 4, "a": 0}]}]), G2),
        ("overlap across lines", score([[(0, 0, 12, 0), (1, 12, 24, 0)], [(0, 30, 12, 0)]]), grid("I go", "now")),
        ("note checks in order", _note(OK, s=9, t=-1, d=0, a=7), G2),
        ("note checks t before d", _note(OK, t=-1, d=0, a=7), G2),
        ("rest-only line has no syllables", score([[(0, 0, 1, 0)], []]), grid("_ _", "go")),
        ("stored syllable_count ignored", score([[(1, 0, 1, 0)]]), {"lines": [{"cells": [{"syllable": "a"}], "syllable_count": 5, "raw_text": "a"}]}),
    ]
    cases = [{"name": name, "score": raw, "grid": g} for name, raw, g in hand]
    # generated: valid random scores and single mutations of them
    mutations: list[tuple[str, Callable[[dict[str, Any], Lcg], None]]] = [
        ("shift a note earlier", _mut_earlier),
        ("lengthen a note", _mut_longer),
        ("syllable past the end", _mut_syllable),
        ("accent out of range", _mut_accent),
        ("pickup too long", _mut_pickup),
        ("drop a line", _mut_drop_line),
    ]
    for index in range(30):
        g = random_grid(rng)
        raw = random_score(rng, g)
        cases.append({"name": f"random valid {index}", "score": raw, "grid": g})
        if any(line["notes"] for line in raw["lines"]):
            name, mutate = mutations[index % len(mutations)]
            mutated = copy.deepcopy(raw)
            mutate(mutated, rng)
            cases.append({"name": f"random {name} {index}", "score": mutated, "grid": g})
    for case in cases:
        result = validate(case["score"], case["grid"])
        if isinstance(result, Invalid):
            expect: dict[str, Any] = {"ok": False, "reason": result.error.reason.value}
            if result.error.line is not None:
                expect["line"] = result.error.line
            if result.error.note is not None:
                expect["note"] = result.error.note
        else:
            expect = {"ok": True}
        case["expect"] = expect
    return cases


def _placed(raw: dict[str, Any]) -> list[dict[str, Any]]:
    return [note for line in raw["lines"] for note in line["notes"]]


def _mut_earlier(raw: dict[str, Any], rng: Lcg) -> None:
    note = rng.pick(_placed(raw))
    note["t"] = max(0, note["t"] - rng.pick((1, 12, 48, 500)))


def _mut_longer(raw: dict[str, Any], rng: Lcg) -> None:
    note = rng.pick(_placed(raw))
    note["d"] += rng.pick((1, 24, 200))


def _mut_syllable(raw: dict[str, Any], rng: Lcg) -> None:
    rng.pick(_placed(raw))["s"] += 50


def _mut_accent(raw: dict[str, Any], rng: Lcg) -> None:
    rng.pick(_placed(raw))["a"] = 3


def _mut_pickup(raw: dict[str, Any], rng: Lcg) -> None:
    first = raw["meters"][0]
    raw["pickup"] = first["num"] * 192 // first["den"]


def _mut_drop_line(raw: dict[str, Any], rng: Lcg) -> None:
    raw["lines"].pop()


# ---- timing ---------------------------------------------------------------------------------

TIMING_SCORES: list[tuple[str, dict[str, Any]]] = [
    ("4/4", timing_only([(0, 4, 4)])),
    ("4/4 pickup 48", timing_only([(0, 4, 4)], pickup=48)),
    ("4/4 pickup 30", timing_only([(0, 4, 4)], pickup=30)),
    ("3/4", timing_only([(0, 3, 4)])),
    ("2/4", timing_only([(0, 2, 4)])),
    ("2/2", timing_only([(0, 2, 2)])),
    ("6/8", timing_only([(0, 6, 8)])),
    ("6/8 pickup 24", timing_only([(0, 6, 8)], pickup=24)),
    ("6/8 pickup 72", timing_only([(0, 6, 8)], pickup=72)),
    ("9/8", timing_only([(0, 9, 8)])),
    ("12/8", timing_only([(0, 12, 8)])),
    ("3/8", timing_only([(0, 3, 8)])),
    ("5/8", timing_only([(0, 5, 8)])),
    ("7/8", timing_only([(0, 7, 8)])),
    ("6/4", timing_only([(0, 6, 4)])),
    ("5/4", timing_only([(0, 5, 4)])),
    ("6/16", timing_only([(0, 6, 16)])),
    ("12/16", timing_only([(0, 12, 16)])),
    ("7/16", timing_only([(0, 7, 16)])),
    ("1/32", timing_only([(0, 1, 32)])),
    ("3/1", timing_only([(0, 3, 1)])),
    ("32/32", timing_only([(0, 32, 32)])),
    ("spec example", timing_only([(0, 4, 4), (2, 3, 4), (3, 6, 8)], pickup=24)),
    ("meter changes every bar", timing_only([(0, 4, 4), (1, 3, 4), (2, 7, 8), (3, 2, 2), (4, 5, 16), (5, 12, 8)], pickup=6)),
    ("late change", timing_only([(0, 3, 4), (2048, 6, 8)])),
    ("tempo map", timing_only([(0, 4, 4)], tempos=[(0, 120), (1, 60)])),
    ("tempo map with pickup", timing_only([(0, 4, 4)], tempos=[(0, 60), (1, 120)], pickup=48)),
    ("tempo and meter changes", timing_only([(0, 4, 4), (1, 7, 8)], tempos=[(0, 90.5), (1, 133.33), (5, 20), (6, 400)], pickup=30)),
    ("tempo decimals", timing_only([(0, 3, 4)], tempos=[(0, 99.99), (3, 72.5), (4, 85.25)], pickup=96)),
    ("extreme tempos", timing_only([(0, 1, 32), (10, 32, 1)], tempos=[(0, 20), (5, 400), (11, 20)])),
]


def _sample_ticks(raw: dict[str, Any], rng: Lcg) -> list[int]:
    s = check_score(raw, None)
    ticks = {0, 1, 5, 6, 11, 12, 23, 24, 36, 47, 48, 72, 95, 96, 143, 144, 191, 192, 193, 384, 1000, MAX_TICKS}
    for bar in range(-1, 8):
        start = bar_start_tick(s, bar)
        ticks.update({start, start + 1, max(0, start - 1)})
    ticks.add(s.pickup)
    for _ in range(8):
        ticks.add(rng.below(4000))
    return sorted(ticks)


def timing_cases(rng: Lcg) -> dict[str, list[Case]]:
    sections: dict[str, list[Case]] = {"bars": [], "bar_at_tick": [], "beat_unit": [], "metric_weight": [], "tick_to_ms": []}
    with_notes = [
        ("notes end in pickup", score([[(0, 0, 12, 0)]], pickup=24), grid("a")),
        ("notes end at bar 0", score([[(0, 0, 24, 0)]], pickup=24), grid("a")),
        ("notes end inside bar 0", score([[(0, 0, 25, 0)]], pickup=24), grid("a")),
        ("notes end at bar 1", score([[(0, 200, 16, 0)]], pickup=24), grid("a")),
        ("notes end past bar 1", score([[(0, 200, 17, 0)]], pickup=24), grid("a")),
        ("notes across meters", score([[(0, 0, 48, 0), (1, 500, 100, 0)], [(0, 700, 6, 0)]], meters=[(0, 4, 4), (2, 3, 4), (3, 6, 8)], pickup=24), grid("a b", "c")),
        ("max ticks", score([[(0, MAX_TICKS - 6, 6, 0)]], meters=[(0, 1, 32)]), grid("a")),
    ]
    for name, raw in TIMING_SCORES:
        _timing(sections, name, raw, None, rng)
    for name, raw, g in with_notes:
        _timing(sections, name, raw, g, rng, bars_only=True)
    for index in range(10):
        raw = random_score(rng, None)
        _timing(sections, f"random {index}", raw, None, rng)
    return sections


def _timing(sections: dict[str, list[Case]], name: str, raw: dict[str, Any], g: Any, rng: Lcg, bars_only: bool = False) -> None:
    s = check_score(raw, g)
    bars = [{"bar": bar, "start": bar_start_tick(s, bar), "length": bar_length(s, bar)} for bar in [*range(-1, 12), 100, 2047, 2048, 2049, 5000]]
    sections["bars"].append({"name": name, "score": raw, "grid": g, "bars": bars, "end_tick": end_tick(s), "bar_count": bar_count(s)})
    if bars_only:
        return
    ticks = _sample_ticks(raw, rng)
    sections["bar_at_tick"].append(
        {"name": name, "score": raw, "queries": [{"tick": t, "bar": (p := bar_at_tick(s, t)).bar, "offset": p.offset} for t in ticks]}
    )
    sections["beat_unit"].append({"name": name, "score": raw, "queries": [{"tick": t, "beat_unit": beat_unit(s, t)} for t in ticks]})
    dense = sorted(set(range(0, 2 * 576 + 1, 3)) | set(ticks)) if name in {"spec example", "meter changes every bar"} else ticks
    sections["metric_weight"].append({"name": name, "score": raw, "queries": [{"tick": t, "weight": metric_weight(s, t)} for t in dense]})
    sections["tick_to_ms"].append({"name": name, "score": raw, "queries": [{"tick": t, "ms": tick_to_ms(s, t)} for t in ticks]})


# ---- text -----------------------------------------------------------------------------------

NORMALIZE_TEXTS = [
    "Don't,", "HELLO!", "Café", "Café", "’til", "'til", "—", "...", "4ever", "ΣΑΣ",
    "rock-n-roll", "x²", "日本", "", "İ", "Ångström", "naïve", "\"quoted\"", "(yeah)", "O'Brien",
    "١٢", "Ⅰ", "a_b", "Straße", "ﬁne", "ẞ", "ǅ", "emoji\U0001f600", "tab\tbed", "½",
]

LCS_CASES: list[tuple[list[str], list[str]]] = [
    ([], []), (["a"], []), ([], ["a"]), (["a"], ["a"]), (["a"], ["b"]),
    (["la", "la"], ["la", "la", "la"]), (["la", "la", "la"], ["la", "la"]), (["x", "y"], ["y", "x"]),
    (["a", "b", "c"], ["a", "x", "c"]), (["a", "b", "c"], ["c", "b", "a"]), (["", "a"], ["", "a"]), (["", ""], [""]),
    (["a", "b", "a"], ["b", "a", "b"]), (["new", "a", "b"], ["a", "b"]), (["a", "b"], ["new", "a", "b"]),
    (["a", "b", "c", "d"], ["b", "d"]), (["a", "b", "a", "b"], ["b", "a"]), (["a", "a", "b"], ["a", "b", "a"]),
    (["x", "a", "y", "a"], ["a", "x", "a"]), (["a", "b", "c", "a", "b"], ["b", "a", "c", "b", "a"]),
]


def text_cases(rng: Lcg) -> dict[str, list[Case]]:
    normalize = [{"name": repr(text), "text": text, "normalized": normalize_word(text), "key": word_key(text)} for text in NORMALIZE_TEXTS]
    lcs: list[Case] = [{"name": f"hand {i}", "a": a, "b": b} for i, (a, b) in enumerate(LCS_CASES)]
    alphabet = ["a", "b", "c", ""]
    for index in range(20):
        a = [rng.pick(alphabet) for _ in range(rng.below(8))]
        b = [rng.pick(alphabet) for _ in range(rng.below(8))]
        lcs.append({"name": f"random {index}", "a": a, "b": b})
    for case in lcs:
        case["pairs"] = [list(p) for p in lcs_pairs(case["a"], case["b"])]
    lines = [
        grid("Hel-lo, World!")["lines"][0],
        grid("_ _")["lines"][0],
        {"cells": [{"syllable": "la"}, {"syllable": "LA"}]},
        {"cells": [{"syllable": "a", "word_index": 1}, {"syllable": "b", "word_index": 0}, {"syllable": "c", "word_index": 1}]},
        {"cells": [{"syllable": "hel", "word_index": 0, "word": "Hello"}, {"syllable": "lo", "word_index": 0}]},
        {"cells": [{"syllable": "hel", "word_index": 0}, {"syllable": "lo", "word_index": 0, "word": "HELLO"}]},
        {"cells": [{"syllable": "x", "word_index": 0, "word": ""}, {"syllable": "y", "word_index": 0}]},
        {"cells": [{"syllable": "...", "word_index": 0, "word": "..."}, {"rest": True}, {"syllable": "go", "word_index": 1}]},
        {"cells": [{"syllable": None, "word_index": 0}, {"syllable": "a"}, {"rest": False, "syllable": "b", "word_index": 1}]},
        {"cells": []},
    ] + [random_line(rng) for _ in range(10)]
    line_keys = [{"name": f"line {i}", "line": ln, "key": line_key(ln)} for i, ln in enumerate(lines)]
    splices: list[tuple[str, dict[int, str]]] = [
        ("  first line \n\n   \nsecond\n\nthird", {1: "second _"}),
        ("  first line \n\n   \nsecond\n\nthird", {0: "A", 2: "C"}),
        ("a\nb", {}),
        ("a\n\nb", {2: "x"}),
        ("", {0: "x"}),
        ("", {}),
        ("\n\n", {}),
        ("only\r\nwindows\r\n", {1: "w _"}),
        ("a\n\t\nb\n", {0: "_ a", 1: "b _"}),
        ("x", {0: ""}),
    ]
    splice_cases: list[Case] = []
    for raw_text, replacements in splices:
        try:
            expect: dict[str, Any] = {"text": splice_line_texts(raw_text, replacements)}
        except ScoreTextMismatch:
            expect = {"error": "text_mismatch"}
        splice_cases.append({"name": repr(raw_text)[:40], "raw_text": raw_text, "replacements": {str(k): v for k, v in replacements.items()}, "expect": expect})
    return {"normalize_word": normalize, "lcs": lcs, "line_key": line_keys, "text_splice": splice_cases}


# ---- rest link ------------------------------------------------------------------------------


def _link_expect(raw: dict[str, Any], g: Any) -> dict[str, Any]:
    try:
        return {"grid": apply_rest_link(check_score(raw, g), g)}
    except ScoreInvalid as error:
        return _error(error)


def rest_link_cases(rng: Lcg) -> list[Case]:
    hello = grid("hel-lo world")
    tagged = grid("I _ _ _ go")
    tagged["lines"][0]["cells"][1]["mark"] = "keep me"
    kept = grid("I go", "you  stay")
    kept["lines"][1]["raw_text"] = "  You, stay!  "
    extra = grid("I go")
    extra["version"] = 3
    extra["lines"][0]["arranged"] = True
    hand: list[tuple[str, dict[str, Any], Any]] = [
        ("spec example", score([[(0, 0, 24, 0), (1, 72, 24, 0), (2, 96, 24, 0)]]), grid("I go now")),
        ("gap just under a beat", score([[(0, 0, 24, 0), (1, 71, 24, 0)]]), grid("I go")),
        ("gap exactly two beats", score([[(0, 0, 24, 0), (1, 120, 24, 0)]]), grid("I go")),
        ("removes rests keeps first cells", score([[(0, 0, 24, 0), (1, 72, 24, 0)]]), tagged),
        ("removes all rests", score([[(0, 0, 24, 0), (1, 24, 24, 0)]]), grid("I _ _ go")),
        ("unplaced and edges untouched", score([[(0, 0, 12, 0), (1, 200, 12, 0), (3, 400, 12, 0)]]), grid("_ a _ _ b c _ d _")),
        ("unchanged lines keep text", score([[(0, 0, 12, 0), (1, 12, 12, 0)], []]), kept),
        ("rest inside a word", score([[(0, 0, 12, 0), (1, 60, 12, 0), (2, 72, 12, 0)]]), hello),
        ("beat unit after meter change", score([[(0, 168, 24, 0), (1, 264, 12, 0)]], meters=[(0, 4, 4), (1, 6, 8)]), grid("a b")),
        ("beat unit before meter change", score([[(0, 167, 24, 0), (1, 264, 12, 0)]], meters=[(0, 4, 4), (1, 6, 8)]), grid("a b")),
        ("beat unit in pickup", score([[(0, 0, 6, 0), (1, 60, 6, 0)]], meters=[(0, 6, 8)], pickup=48), grid("a b")),
        ("half notes", score([[(0, 0, 24, 0), (1, 300, 24, 0)]], meters=[(0, 2, 2)]), grid("a b")),
        ("thirty-seconds", score([[(0, 0, 6, 0), (1, 30, 6, 0)]], meters=[(0, 7, 32)]), grid("a b")),
        ("null grid", score([]), None),
        ("empty and rest-only lines", score([[], []]), {"lines": [grid("_ _")["lines"][0], {"cells": [], "syllable_count": 0, "raw_text": ""}]}),
        ("other keys kept", score([[(0, 0, 12, 0), (1, 60, 12, 0)]]), extra),
        ("anonymous cells", score([[(0, 0, 12, 0), (1, 100, 12, 0)]]), {"lines": [{"cells": [{"syllable": "la"}, {"syllable": "di"}], "raw_text": "la di"}]}),
        ("many lines", score([[(0, 0, 12, 0), (1, 60, 12, 0)], [(0, 200, 12, 0), (1, 212, 12, 0)], [(1, 400, 24, 0), (2, 600, 24, 0)]]), grid("a b", "c _ d", "e f g")),
        ("score invalid for grid", score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), grid("I")),
    ]
    cases = [{"name": name, "score": raw, "grid": g, "expect": _link_expect(raw, g)} for name, raw, g in hand]
    for index in range(45):
        g = random_grid(rng)
        raw = random_score(rng, g, place_percent=rng.pick((50, 80, 100)))
        cases.append({"name": f"random {index}", "score": raw, "grid": g, "expect": _link_expect(raw, g)})
    return cases


# ---- grid -> score ---------------------------------------------------------------------------


def _edit_expect(raw: dict[str, Any], old: Any, new: Any) -> dict[str, Any]:
    try:
        result = apply_grid_rest_edit(check_score(raw, old), old, new)
    except NotRestOnlyEdit:
        return {"error": "not_rest_only"}
    except ScoreInvalid as error:
        return _error(error)
    return {"score": score_to_json(result.score), "grid": result.grid}


def grid_edit_cases(rng: Lcg) -> tuple[list[Case], list[Case]]:
    three = [(0, 0, 24, 0), (1, 24, 24, 0), (2, 48, 24, 0)]
    hand: list[tuple[str, dict[str, Any], Any, Any]] = [
        ("spec example insert", score([three]), grid("I go now"), grid("I go _ _ now")),
        ("spec example clamp", score([[(0, 0, 24, 0), (1, 48, 24, 0), (2, 72, 24, 0)]]), grid("I _ _ go now"), grid("I go now")),
        ("removal within gap", score([[(0, 0, 24, 0), (1, 120, 24, 0)]]), grid("I _ _ go"), grid("I _ go")),
        ("grid out of step uses delta", score([[(0, 0, 24, 0), (1, 120, 24, 0)]]), grid("I go"), grid("I _ go")),
        ("ripple as far as needed", score([[(0, 0, 24, 0), (1, 24, 24, 0)], [(0, 96, 24, 0), (1, 120, 24, 0)], [(0, 400, 24, 0)]]), grid("a b", "c d", "e"), grid("a _ _ b", "c d", "e")),
        ("ripple never left", score([[(0, 0, 24, 0), (1, 72, 24, 0)], [(0, 200, 24, 0)]]), grid("a _ b", "c"), grid("a b", "c")),
        ("no change", score([three]), grid("I go now"), grid("I go now")),
        ("unplaced neighbours stay", score([[(0, 0, 24, 0), (2, 100, 24, 0)]]), grid("a b c"), grid("a _ b _ _ c _")),
        ("leading and trailing rests stay", score([three]), grid("I go now"), grid("_ _ I go now _")),
        ("drag over a rest", score([[(0, 0, 24, 0), (1, 72, 24, 0), (2, 96, 24, 0)]]), grid("a _ b c"), grid("a b _ c")),
        ("boundaries with current positions", score([[(0, 0, 24, 0), (1, 168, 24, 0), (2, 192, 24, 0)]], meters=[(0, 4, 4), (1, 6, 8)]), grid("a b c"), grid("a _ b _ c")),
        ("insert in a later line ripples", score([[(0, 0, 24, 0)], [(0, 24, 24, 0), (1, 48, 24, 0)], [(0, 72, 24, 0)]]), grid("a", "b c", "d"), grid("a", "b _ _ _ c", "d")),
        ("lines without notes skipped", score([[(0, 0, 24, 0), (1, 24, 24, 0)], [], [(0, 48, 24, 0)]]), grid("a b", "x y", "z"), grid("a _ b", "x _ y", "z")),
        ("not rest only: reorder", score([three]), grid("I go now"), grid("go I now")),
        ("not rest only: word", score([three]), grid("I go now"), grid("I went now")),
        ("not rest only: line added", score([three]), grid("I go now"), grid("I go now", "x")),
        ("score invalid for old grid", score([three]), grid("I go"), grid("I _ go")),
        ("overflow refused", score([[(0, 0, 24, 0), (1, MAX_TICKS - 24, 24, 0)]]), grid("a b"), grid("a _ b")),
        ("extras kept", _with(score([[(0, 0, 24, 2), (1, 24, 24, 1)]], tempos=[(0, 77.5)], pickup=12, snap="1/8t")), grid("a b"), grid("a _ b")),
        ("half-note beats", score([[(0, 0, 24, 0), (1, 24, 24, 0)]], meters=[(0, 3, 2)]), grid("a b"), grid("a _ b")),
    ]
    hand[-2][1]["lines"][0]["notes"][1]["p"] = 64
    edits = [{"name": name, "score": raw, "old_grid": old, "new_grid": new, "expect": _edit_expect(raw, old, new)} for name, raw, old, new in hand]
    for index in range(45):
        old = random_grid(rng)
        raw = random_score(rng, old, place_percent=rng.pick((60, 90, 100)))
        new = rest_edit(rng, old)
        edits.append({"name": f"random {index}", "score": raw, "old_grid": old, "new_grid": new, "expect": _edit_expect(raw, old, new)})
    predicates: list[Case] = []
    pairs = [
        ("rests added", grid("I go"), grid("I _ _ go _")),
        ("leading rest removed", grid("_ I go"), grid("I go")),
        ("line added", grid("I go"), grid("I go", "x")),
        ("reorder", grid("I go"), grid("go I")),
        ("word changed", grid("I go"), grid("I goes")),
        ("null and empty", None, {"lines": []}),
        ("stress differs", grid("^I go"), grid("I go")),
        ("word key differs", {"lines": [{"cells": [{"syllable": "a", "word_index": 0, "word": "a"}]}]}, {"lines": [{"cells": [{"syllable": "a", "word_index": 0, "word": "A"}]}]}),
        ("word_index differs", {"lines": [{"cells": [{"syllable": "a", "word_index": 0}]}]}, {"lines": [{"cells": [{"syllable": "a", "word_index": 1}]}]}),
        ("missing vs null syllable", {"lines": [{"cells": [{"word_index": 0}]}]}, {"lines": [{"cells": [{"syllable": None, "word_index": 0}]}]}),
        ("rest-only lines", grid("_ _"), grid("_")),
        ("syllable split", grid("hel-lo"), grid("hello")),
    ]
    for name, old, new in pairs:
        predicates.append({"name": name, "old_grid": old, "new_grid": new, "expect": is_rest_only_edit(old, new)})
    for index in range(20):
        old = random_grid(rng)
        new = rest_edit(rng, old) if index % 2 == 0 else text_edit(rng, old)
        predicates.append({"name": f"random {index}", "old_grid": old, "new_grid": new, "expect": is_rest_only_edit(old, new)})
    return edits, predicates


# ---- reconcile ------------------------------------------------------------------------------


def _reconcile_expect(raw: dict[str, Any], old: Any, new: Any) -> dict[str, Any]:
    try:
        return {"score": score_to_json(reconcile(check_score(raw, old), old, new))}
    except ScoreInvalid as error:
        return _error(error)


def reconcile_cases(rng: Lcg) -> list[Case]:
    one = [(0, 0, 12, 1), (1, 12, 12, 0), (2, 48, 24, 2)]
    two_lines = [[(0, 0, 12, 0), (1, 12, 12, 0)], [(0, 48, 12, 0), (1, 60, 12, 0)]]
    a0 = {"syllable": "a", "word_index": 0, "word": "ab"}
    a1 = {"syllable": "b", "word_index": 0, "word": "ab"}
    b0 = {"syllable": "c", "word_index": 1, "word": "c"}
    dots = [{"syllable": "...", "word_index": 0, "word": "..."}, {"syllable": "go", "word_index": 1, "word": "go"}]
    extras = score([[(0, 0, 12, 0), (1, 12, 12, 0)]], meters=[(0, 3, 4), (4, 6, 8)], tempos=[(0, 100), (2, 80)], pickup=24, snap="1/32")
    extras["lines"][0]["notes"][1]["p"] = {"midi": 60}
    beautiful = score([[(0, 0, 12, 0), (1, 12, 12, 0), (2, 24, 12, 0), (3, 48, 12, 1)]])
    hand: list[tuple[str, dict[str, Any], Any, Any]] = [
        ("identical", score([one]), grid("hel-lo world"), grid("hel-lo world")),
        ("word inserted", score([one]), grid("hel-lo world"), grid("hel-lo big world")),
        ("word changed", score([one]), grid("hel-lo world"), grid("hel-los world")),
        ("word deleted", score([one]), grid("hel-lo world"), grid("world")),
        ("syllables grow", beautiful, grid("beau-ti-ful day"), grid("beau-ti-f-ul day")),
        ("syllables shrink", beautiful, grid("beau-ti-ful day"), grid("beau-tiful day")),
        ("case and punctuation", score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), grid("I go"), grid("I, GO!")),
        ("rests shift ordinals only", score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), grid("I go"), grid("_ I _ go _")),
        ("same count pairs by index: swap", score(two_lines), grid("a b", "c d"), grid("c d", "a b")),
        ("same count pairs by index: edits", score(two_lines), grid("a b", "c d"), grid("a x", "y d")),
        ("line inserted between", score(two_lines), grid("a b", "c d"), grid("a b", "new line", "c d")),
        ("line inserted first", score(two_lines), grid("a b", "c d"), grid("new", "a b", "c d")),
        ("line deleted", score(two_lines), grid("a b", "c d"), grid("c d")),
        ("all lines deleted", score(two_lines), grid("a b", "c d"), grid()),
        ("edited and inserted", score(two_lines), grid("a b", "c d"), grid("a x", "c d", "e")),
        ("repeated lines", score(two_lines), grid("la la", "la la"), grid("la la", "la la", "la la")),
        ("interleaved reorder drops", score([[(0, 0, 12, 0), (1, 12, 12, 0), (2, 24, 12, 0)]]), {"lines": [finish_line([a0, b0, a1])]}, {"lines": [finish_line([a0, a1, b0])]}),
        ("punctuation word matches itself", score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), {"lines": [finish_line(dots)]}, {"lines": [finish_line(dots + [{"syllable": "now", "word_index": 2, "word": "now"}])]}),
        ("anonymous cells", score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), {"lines": [finish_line([{"syllable": "la"}, {"syllable": "di"}])]}, {"lines": [finish_line([{"syllable": "di"}])]}),
        ("extras and settings kept", extras, grid("a b"), grid("x a b")),
        ("null old grid", score([]), None, grid("a")),
        ("null new grid", score([[(0, 0, 12, 0)]]), grid("a"), None),
        ("repeated words", score([[(0, 0, 12, 0), (1, 12, 12, 0), (2, 24, 12, 0)]]), grid("na na na"), grid("na na")),
        ("repeated words grow", score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), grid("na na"), grid("na na na")),
        ("apostrophe variants", score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), grid("don't stop"), grid("don’t stop")),
        ("unicode composition", score([[(0, 0, 12, 0)]]), grid("café"), grid("café")),
        ("score invalid for old grid", score([[(0, 0, 12, 0), (1, 12, 12, 0)]]), grid("a"), grid("a b")),
    ]
    cases = [{"name": name, "score": raw, "old_grid": old, "new_grid": new, "expect": _reconcile_expect(raw, old, new)} for name, raw, old, new in hand]
    for index in range(55):
        old = random_grid(rng)
        raw = random_score(rng, old, place_percent=rng.pick((60, 90, 100)))
        new = text_edit(rng, old)
        cases.append({"name": f"random {index}", "score": raw, "old_grid": old, "new_grid": new, "expect": _reconcile_expect(raw, old, new)})
    return cases


# ---- draft ----------------------------------------------------------------------------------


def _draft_expect(g: Any, meter: Any, bpm: Any, note: Any) -> dict[str, Any]:
    try:
        return {"score": score_to_json(draft_from_grid(g, meter, bpm, note))}
    except ScoreInvalid as error:
        return _error(error)


def draft_cases(rng: Lcg) -> list[Case]:
    stress = {"lines": [finish_line([{"syllable": "a", "stress": 1}, {"syllable": "b", "stress": 2}, {"syllable": "c", "stress": 0}, {"syllable": "d"}, {"syllable": "e", "stress": 1, "rest": False}])]}
    rest_only = {"lines": [{"cells": [{"rest": True}] * 5, "raw_text": "_ _ _ _ _"}, {"cells": [], "raw_text": ""}, {"cells": [{"syllable": "x"}], "raw_text": "x"}]}
    # 32/1 bars are 6144 ticks: line 64 starts at bar 64 = MAX_TICKS
    tall = {"lines": [{"cells": [{"syllable": "x"}], "raw_text": "x"} for _ in range(65)]}
    fits = {"lines": tall["lines"][:64]}
    m44 = {"num": 4, "den": 4}
    hand: list[tuple[str, Any, Any, Any, Any]] = [
        ("spec example", grid("^I _ ^go", "^now"), m44, 90, "1/8"),
        ("stress to accent", stress, m44, 90, "1/8"),
        ("sixteenths", grid("a b c"), m44, 90, "1/16"),
        ("thirty-seconds", grid("a b c"), m44, 90, "1/32"),
        ("eighth triplets", grid("a b c"), m44, 90, "1/8t"),
        ("sixteenth triplets", grid("a b c"), m44, 90, "1/16t"),
        ("lines on next bar", grid("a b c d e f", "g", "h i j k l m n", "o"), {"num": 3, "den": 4}, 90, "1/8"),
        ("rests are beats", grid("_ a _ b _ _", "c"), {"num": 6, "den": 8}, 90, "1/16"),
        ("rest-only and empty lines", rest_only, m44, 90, "1/8"),
        ("null grid", None, m44, 90, "1/8"),
        ("empty grid", {"lines": []}, m44, 90, "1/8"),
        ("decimal bpm", grid("a"), m44, 72.25, "1/8"),
        ("half-note meter", grid("a _ b"), {"num": 3, "den": 2}, 60, "1/8"),
        ("meter num 0", grid("a"), {"num": 0, "den": 4}, 90, "1/8"),
        ("meter den 3", grid("a"), {"num": 4, "den": 3}, 90, "1/8"),
        ("meter num string", grid("a"), {"num": "4", "den": 4}, 90, "1/8"),
        ("meter missing den", grid("a"), {"num": 4}, 90, "1/8"),
        ("bpm 19", grid("a"), m44, 19, "1/8"),
        ("bpm 3 decimals", grid("a"), m44, 90.123, "1/8"),
        ("bpm null", grid("a"), m44, None, "1/8"),
        ("meter before bpm", grid("a"), {"num": 0, "den": 4}, None, "x"),
        ("note 1/4", grid("a"), m44, 90, "1/4"),
        ("last bar fits", fits, {"num": 32, "den": 1}, 90, "1/8"),
        ("overflow", tall, {"num": 32, "den": 1}, 90, "1/8"),
    ]
    cases = [{"name": name, "grid": g, "meter": meter, "bpm": bpm, "note": note, "expect": _draft_expect(g, meter, bpm, note)} for name, g, meter, bpm, note in hand]
    notes = ["1/8", "1/16", "1/32", "1/8t", "1/16t"]
    meters = [(4, 4), (3, 4), (6, 8), (7, 8), (2, 2), (5, 16), (12, 8), (1, 1), (9, 8), (5, 4)]
    for index in range(30):
        g = random_grid(rng)
        num, den = meters[index % len(meters)]
        meter = {"num": num, "den": den}
        note = notes[index % len(notes)]
        bpm = rng.pick((60, 90, 120, 97.5))
        cases.append({"name": f"random {index}", "grid": g, "meter": meter, "bpm": bpm, "note": note, "expect": _draft_expect(g, meter, bpm, note)})
    return cases


# ---- file -----------------------------------------------------------------------------------


CELL_KEYS = {"syllable", "stress", "word_index", "rest", "word"}
LINE_KEYS = {"cells", "syllable_count", "raw_text"}


def _has_unknown_grid_keys(g: Any) -> bool:
    if g is None:
        return False
    if set(g) - {"lines"}:
        return True
    return any(set(line) - LINE_KEYS or any(set(cell) - CELL_KEYS for cell in line["cells"]) for line in g["lines"])


def _flag_unknown_keys(vectors: dict[str, Any]) -> None:
    """Mark cases whose grids carry keys outside the GridCell / GridLine / GridData wire types."""
    for section in ("rest_link", "grid_rest_edit", "reconcile", "rest_only_edit", "draft_from_grid"):
        for case in vectors[section]:
            if any(_has_unknown_grid_keys(case.get(key)) for key in ("grid", "old_grid", "new_grid")):
                case["unknown_grid_keys"] = True


def build_vectors() -> dict[str, Any]:
    rng = Lcg(SEED)
    vectors: dict[str, Any] = {
        "version": 1,
        "constants": {
            "ppq": PPQ,
            "whole": WHOLE,
            "max_bar": MAX_BAR,
            "max_ticks": MAX_TICKS,
            "max_meters": 256,
            "max_tempos": 256,
            "snap_ticks": {snap.value: ticks for snap, ticks in SNAP_TICKS.items()},
            "ms_tolerance": 1e-6,
        },
        "validate": validate_cases(rng),
    }
    vectors.update(timing_cases(rng))
    vectors.update(text_cases(rng))
    vectors["rest_link"] = rest_link_cases(rng)
    edits, predicates = grid_edit_cases(rng)
    vectors["rest_only_edit"] = predicates
    vectors["grid_rest_edit"] = edits
    vectors["reconcile"] = reconcile_cases(rng)
    vectors["draft_from_grid"] = draft_cases(rng)
    _flag_unknown_keys(vectors)
    return vectors


def render_vectors() -> str:
    vectors = build_vectors()
    parts = ["{"]
    keys = list(vectors)
    for index, key in enumerate(keys):
        value = vectors[key]
        comma = "," if index + 1 < len(keys) else ""
        if isinstance(value, list):
            rows = [json.dumps(case, ensure_ascii=True, separators=(",", ":"), allow_nan=False) for case in value]
            parts.append(f"{json.dumps(key)}: [\n" + ",\n".join(rows) + f"\n]{comma}")
        else:
            parts.append(f"{json.dumps(key)}: {json.dumps(value, ensure_ascii=True, separators=(',', ':'))}{comma}")
    parts.append("}")
    return "\n".join(parts) + "\n"


def main() -> int:
    VECTORS_PATH.write_text(render_vectors(), encoding="utf-8")
    print(f"wrote {VECTORS_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
