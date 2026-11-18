"""Read-only views of a grid as the score algorithms see it (spec/score_format.md section 3)."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from app.services.score.types import GridJson

Cell = Mapping[str, Any]


def is_rest(cell: Cell) -> bool:
    return cell.get("rest") is True


def grid_lines(grid: GridJson) -> list[Mapping[str, Any]]:
    if grid is None:
        return []
    return list(grid.get("lines") or [])


def line_cells(line: Mapping[str, Any]) -> list[Cell]:
    return list(line.get("cells") or [])


def syllable_positions(cells: Sequence[Cell]) -> list[int]:
    """``syl[k]``: the cell index of the syllable with ordinal ``k``."""
    return [index for index, cell in enumerate(cells) if not is_rest(cell)]


def syllable_counts(grid: GridJson) -> list[int]:
    return [len(syllable_positions(line_cells(line))) for line in grid_lines(grid)]


def rests_between(cells: Sequence[Cell]) -> list[int]:
    """For each ``k`` in ``0 .. n-2``: the rest cells between syllables ``k`` and ``k+1``."""
    syl = syllable_positions(cells)
    return [syl[k + 1] - syl[k] - 1 for k in range(len(syl) - 1)]


def _word_index(cell: Cell) -> int | None:
    value = cell.get("word_index")
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    return None


def _syllable_text(cell: Cell) -> str:
    value = cell.get("syllable")
    return value if isinstance(value, str) else ""


@dataclass(frozen=True, slots=True)
class WordGroup:
    spelling: str
    ordinals: tuple[int, ...]


def word_groups(cells: Sequence[Cell]) -> list[WordGroup]:
    """Groups by ``word_index`` in order of first appearance; anonymous syllables stand alone.

    The spelling follows ``rest_marks.rebuild_line_text``: the first non-empty ``word`` of the
    group's cells, else the concatenated syllables."""
    order: list[int | tuple[str, int]] = []
    ordinals: dict[int | tuple[str, int], list[int]] = {}
    spelled: dict[int, str] = {}
    syllables: dict[int | tuple[str, int], list[str]] = {}
    ordinal = 0
    for cell in cells:
        if is_rest(cell):
            continue
        index = _word_index(cell)
        key: int | tuple[str, int] = ("anon", ordinal) if index is None else index
        if key not in ordinals:
            order.append(key)
            ordinals[key] = []
            syllables[key] = []
        ordinals[key].append(ordinal)
        syllables[key].append(_syllable_text(cell))
        word = cell.get("word")
        if index is not None and index not in spelled and isinstance(word, str) and word:
            spelled[index] = word
        ordinal += 1
    groups: list[WordGroup] = []
    for key in order:
        spelling = spelled.get(key) if isinstance(key, int) else None
        groups.append(WordGroup(spelling if spelling is not None else "".join(syllables[key]), tuple(ordinals[key])))
    return groups


def syllable_identity(cells: Sequence[Cell]) -> list[tuple[Any, Any]]:
    """What a rest-only edit must keep per syllable: ``(syllable, word_index)`` as JSON values."""
    return [(cell.get("syllable"), cell.get("word_index")) for cell in cells if not is_rest(cell)]
