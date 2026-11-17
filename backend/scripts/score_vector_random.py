"""Deterministic random grids, scores and edits for the score vectors and property tests.

A self-contained 64-bit LCG keeps the generated vectors independent of the Python version
(``random`` may change between releases). Everything here builds plain JSON.
"""

from __future__ import annotations

import copy
from collections.abc import Sequence
from typing import Any, TypeVar

from app.services.rest_marks import rebuild_line_text

T = TypeVar("T")

VOCABULARY: tuple[str, ...] = (
    "I", "go", "hel-lo", "world", "beau-ti-ful", "to-night", "run", "a-way", "don't", "stop",
    "the", "mu-sic", "in", "my", "head", "ev-ery-thing", "is", "a-live", "cra-zy", "love",
    "Ba-by,", "yeah!", "o-ver", "and", "un-der", "fire", "wa-ter", "we", "'til", "Caf-é",
)
METERS: tuple[tuple[int, int], ...] = (
    (4, 4), (3, 4), (2, 4), (6, 8), (12, 8), (9, 8), (7, 8), (5, 4), (2, 2), (3, 8), (6, 4), (5, 8), (1, 4), (6, 16),
)
BPMS: tuple[int | float, ...] = (90, 120, 60, 72.5, 140, 99.99, 20, 400, 133.33, 85)
DURATIONS: tuple[int, ...] = (6, 8, 12, 16, 18, 24, 36, 48, 72, 96)
GAPS: tuple[int, ...] = (0, 0, 0, 6, 12, 24, 36, 48, 50, 72, 96, 100, 144)


class Lcg:
    """Knuth's MMIX LCG; ``below(n)`` uses the high bits."""

    def __init__(self, seed: int) -> None:
        self.state = seed & 0xFFFFFFFFFFFFFFFF

    def next(self) -> int:
        self.state = (self.state * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        return self.state >> 33

    def below(self, n: int) -> int:
        return self.next() % n

    def between(self, lo: int, hi: int) -> int:
        return lo + self.below(hi - lo + 1)

    def chance(self, percent: int) -> bool:
        return self.below(100) < percent

    def pick(self, items: Sequence[T]) -> T:
        return items[self.below(len(items))]


def word_cells(token: str, word_index: int, rng: Lcg) -> list[dict[str, Any]]:
    parts = token.split("-")
    word = "".join(parts)
    out = []
    for position, part in enumerate(parts):
        stress = rng.pick((0, 1, 1, 2)) if position == 0 else rng.pick((0, 0, 1, 2))
        out.append({"syllable": part, "stress": stress, "word_index": word_index, "word": word})
    return out


def finish_line(cells: list[dict[str, Any]]) -> dict[str, Any]:
    count = sum(1 for c in cells if c.get("rest") is not True)
    return {"cells": cells, "syllable_count": count, "raw_text": rebuild_line_text(cells)}


def random_line(rng: Lcg, words: int | None = None, rest_percent: int = 20) -> dict[str, Any]:
    if rng.chance(5):
        return finish_line([{"rest": True} for _ in range(rng.between(1, 3))])
    cells: list[dict[str, Any]] = []
    count = words if words is not None else rng.between(1, 5)
    for index in range(count):
        while rng.chance(rest_percent):
            cells.append({"rest": True})
        cells.extend(word_cells(rng.pick(VOCABULARY), index, rng))
    while rng.chance(rest_percent // 2):
        cells.append({"rest": True})
    return finish_line(cells)


def random_grid(rng: Lcg, lines: int | None = None) -> dict[str, Any]:
    return {"lines": [random_line(rng) for _ in range(lines if lines is not None else rng.between(1, 4))]}


def random_meters(rng: Lcg) -> list[dict[str, int]]:
    meters, bar = [], 0
    for index in range(rng.pick((1, 1, 2, 3))):
        num, den = rng.pick(METERS)
        bar = 0 if index == 0 else bar + rng.between(1, 3)
        meters.append({"bar": bar, "num": num, "den": den})
    return meters


def random_tempos(rng: Lcg) -> list[dict[str, Any]]:
    tempos, bar = [], 0
    for index in range(rng.pick((1, 1, 2, 3))):
        bar = 0 if index == 0 else bar + rng.between(1, 4)
        tempos.append({"bar": bar, "bpm": rng.pick(BPMS)})
    return tempos


def syllable_count(line: dict[str, Any]) -> int:
    return sum(1 for c in line["cells"] if c.get("rest") is not True)


def random_score(rng: Lcg, grid: dict[str, Any] | None, place_percent: int = 80) -> dict[str, Any]:
    meters = random_meters(rng)
    first = meters[0]["num"] * 192 // meters[0]["den"]
    pickup = 0 if rng.chance(60) else rng.below(first // 6) * 6
    cursor = 0 if rng.chance(50) else rng.pick((0, 12, 24, 48))
    lines = []
    for line in (grid or {"lines": []})["lines"]:
        notes = []
        for s in range(syllable_count(line)):
            if not rng.chance(place_percent):
                continue
            cursor += rng.pick(GAPS)
            d = rng.pick(DURATIONS)
            notes.append({"s": s, "t": cursor, "d": d, "a": rng.pick((0, 0, 1, 2))})
            cursor += d
        if rng.chance(30):
            cursor += rng.pick((0, 96, 192))
        lines.append({"notes": notes})
    return {
        "v": 1,
        "ppq": 48,
        "pickup": pickup,
        "meters": meters,
        "tempos": random_tempos(rng),
        "snap": rng.pick(("1/8", "1/16", "1/32", "1/8t", "1/16t")),
        "lines": lines,
    }


def _reindexed(cells: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Re-base word_index by order of first appearance (as the clients do after an edit)."""
    mapping: dict[int, int] = {}
    out = []
    for cell in cells:
        if cell.get("rest") is True or not isinstance(cell.get("word_index"), int):
            out.append(cell)
            continue
        new = dict(cell)
        new["word_index"] = mapping.setdefault(cell["word_index"], len(mapping))
        out.append(new)
    return out


def rest_edit(rng: Lcg, grid: dict[str, Any]) -> dict[str, Any]:
    """Add, remove or move rest cells (syllables untouched) in one or two lines."""
    new = copy.deepcopy(grid)
    if not new["lines"]:
        return new
    for _ in range(rng.pick((1, 1, 2))):
        cells = new["lines"][rng.below(len(new["lines"]))]["cells"]
        kind = rng.below(3)
        rests = [i for i, c in enumerate(cells) if c.get("rest") is True]
        if kind == 0 or not rests:
            at = rng.below(len(cells) + 1)
            for _ in range(rng.between(1, 3)):
                cells.insert(at, {"rest": True})
        elif kind == 1:
            del cells[rng.pick(rests)]
        else:
            del cells[rng.pick(rests)]
            cells.insert(rng.below(len(cells) + 1), {"rest": True})
    for index, line in enumerate(new["lines"]):
        new["lines"][index] = finish_line(line["cells"])
    return new


def text_edit(rng: Lcg, grid: dict[str, Any]) -> dict[str, Any]:
    """A random text-level edit: words inserted, deleted, changed or re-split; lines inserted,
    deleted or duplicated; a syllable dragged across another (reorder)."""
    new = copy.deepcopy(grid)
    lines = new["lines"]
    kind = rng.below(9) if lines else 4
    if kind <= 4 and lines:
        index = rng.below(len(lines))
        groups = _groups(lines[index]["cells"])
        cells = lines[index]["cells"]
        if kind == 0 or not groups:  # insert a word
            cells = _insert_word(rng, cells)
        elif kind == 1:  # delete a word
            gone = rng.pick(list(groups))
            cells = [c for c in cells if c.get("word_index") != gone or c.get("rest") is True]
        elif kind == 2:  # change a word
            target = rng.pick(list(groups))
            replacement = word_cells(rng.pick(VOCABULARY), target, rng)
            positions = [i for i, c in enumerate(cells) if c.get("word_index") == target and c.get("rest") is not True]
            cells = cells[: positions[0]] + replacement + [c for i, c in enumerate(cells) if i > positions[0] and i not in positions]
        elif kind == 3:  # re-split a word (syllable count change, same spelling)
            target = rng.pick(list(groups))
            positions = [i for i, c in enumerate(cells) if c.get("word_index") == target and c.get("rest") is not True]
            word = cells[positions[0]].get("word") or "x"
            cut = rng.between(1, max(1, len(word) - 1))
            parts = [word] if len(positions) > 1 or len(word) < 2 else [word[:cut], word[cut:]]
            replacement = [{"syllable": p, "stress": 0, "word_index": target, "word": word} for p in parts]
            cells = cells[: positions[0]] + replacement + [c for i, c in enumerate(cells) if i > positions[0] and i not in positions]
        else:  # drag one syllable across another
            syl = [i for i, c in enumerate(cells) if c.get("rest") is not True]
            if len(syl) >= 2:
                k = rng.below(len(syl) - 1)
                a, b = syl[k], syl[k + 1]
                cells = list(cells)
                cells[a], cells[b] = cells[b], cells[a]
        lines[index] = finish_line(_reindexed(cells))
    elif kind == 5:  # insert a line
        lines.insert(rng.below(len(lines) + 1), random_line(rng))
    elif kind == 6 and lines:  # delete a line
        del lines[rng.below(len(lines))]
    elif kind == 7 and lines:  # duplicate a line
        at = rng.below(len(lines))
        lines.insert(at, copy.deepcopy(lines[at]))
    elif lines:  # two edits in one go
        return text_edit(rng, text_edit(rng, new))
    return new


def _groups(cells: list[dict[str, Any]]) -> dict[int, int]:
    out: dict[int, int] = {}
    for cell in cells:
        if cell.get("rest") is not True and isinstance(cell.get("word_index"), int):
            out[cell["word_index"]] = out.get(cell["word_index"], 0) + 1
    return out


def _insert_word(rng: Lcg, cells: list[dict[str, Any]]) -> list[dict[str, Any]]:
    starts = [i for i, c in enumerate(cells) if c.get("rest") is True or i == 0 or c.get("word_index") != cells[i - 1].get("word_index")]
    at = rng.pick(starts + [len(cells)]) if cells else 0
    return cells[:at] + word_cells(rng.pick(VOCABULARY), 10_000, rng) + cells[at:]
