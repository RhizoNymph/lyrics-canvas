"""Compact builders for score tests.

``line("^hel-lo _ world")``: words split on whitespace, ``_`` is a rest, ``-`` splits syllables,
``^`` before a syllable gives it stress 1 (else 0). Cells carry syllable, stress, word_index, word.
``score([[(s, t, d, a), ...], ...])`` builds raw score JSON.
"""

from __future__ import annotations

from typing import Any

from app.services.rest_marks import rebuild_line_text


def cells(text: str) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    word_index = 0
    for token in text.replace("_", " _ ").split():
        if token == "_":
            out.append({"rest": True})
            continue
        parts = token.split("-")
        word = "".join(p.lstrip("^") for p in parts)
        for part in parts:
            stress = 1 if part.startswith("^") else 0
            out.append({"syllable": part.lstrip("^"), "stress": stress, "word_index": word_index, "word": word})
        word_index += 1
    return out


def line_from_cells(cell_list: list[dict[str, Any]]) -> dict[str, Any]:
    count = sum(1 for c in cell_list if c.get("rest") is not True)
    return {"cells": cell_list, "syllable_count": count, "raw_text": rebuild_line_text(cell_list)}


def line(text: str) -> dict[str, Any]:
    return line_from_cells(cells(text))


def grid(*texts: str) -> dict[str, Any]:
    return {"lines": [line(t) for t in texts]}


def score(
    lines: list[list[tuple[int, int, int, int]]],
    meters: list[tuple[int, int, int]] | None = None,
    tempos: list[tuple[int, float]] | None = None,
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


def notes_of(score_json: dict[str, Any]) -> list[list[tuple[int, int, int, int]]]:
    return [[(n["s"], n["t"], n["d"], n["a"]) for n in ln["notes"]] for ln in score_json["lines"]]


def texts_of(grid_json: dict[str, Any]) -> list[str]:
    return [ln["raw_text"] for ln in grid_json["lines"]]
