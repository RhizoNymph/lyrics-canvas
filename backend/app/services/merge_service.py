"""Block merging: combine two or more snippets into one (pure; no I/O).

Ported from the web client's former ``frontend/src/lib/merge``; both clients now call
``POST /api/canvases/{canvas_id}/merge``, which uses these functions. Blocks combine in
canvas-position order (never request order), the first block supplies type, grid mode,
position and group, and grids are carried over cell by cell (rests and stress kept).
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from app.models.snippet import GridMode, SnippetType
from app.schemas.merge import MergeMode
from app.schemas.snippet import GridCellModel, GridDataModel, GridLineModel


class MergeError(Exception):
    """Base class for merge failures."""


class TooFewSnippetsError(MergeError):
    """Fewer than two distinct snippets were given."""


class MergeSourcesNotFoundError(MergeError):
    """Some requested snippets are not owned by the caller or not in the canvas."""

    def __init__(self, missing_ids: Sequence[str]):
        super().__init__(f"snippets not found: {', '.join(missing_ids)}")
        self.missing_ids = tuple(missing_ids)


class MergePersistError(MergeError):
    """Storing the merge failed; the transaction was rolled back and nothing changed."""


@dataclass(frozen=True, slots=True)
class MergeSource:
    """The parts of a snippet a merge reads."""

    id: str
    raw_text: str
    grid: GridDataModel | None
    snippet_type: SnippetType
    grid_mode: GridMode
    beats_per_line: int | None
    group_id: str | None
    position_x: float
    position_y: float
    tag_ids: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class MergedSnippet:
    """Everything needed to create the replacement snippet."""

    raw_text: str
    grid: GridDataModel
    snippet_type: SnippetType
    grid_mode: GridMode
    beats_per_line: int | None
    group_id: str | None
    position_x: float
    position_y: float
    tag_ids: tuple[str, ...]  # union of the sources' tags, first-seen order
    source_ids: tuple[str, ...]  # the sources in merge order; deleted by the merge


def order_by_position(sources: Sequence[MergeSource], mode: MergeMode) -> list[MergeSource]:
    """Vertical reads top-to-bottom (y, then x); horizontal left-to-right (x, then y); ties by id."""
    match mode:
        case MergeMode.vertical:
            return sorted(sources, key=lambda s: (s.position_y, s.position_x, s.id))
        case MergeMode.horizontal:
            return sorted(sources, key=lambda s: (s.position_x, s.position_y, s.id))


def text_lines(raw_text: str) -> list[str]:
    """Split text the way the syllabifier does: trimmed, blank lines dropped."""
    return [line.strip() for line in raw_text.split("\n") if line.strip()]


def word_count(text: str) -> int:
    return sum(len(line.split()) for line in text_lines(text))


def lines_of(source: MergeSource) -> list[GridLineModel]:
    """A source's grid lines; without a grid, its text lines with no cells."""
    if source.grid is not None:
        return list(source.grid.lines)
    return [GridLineModel(cells=[], syllable_count=0, raw_text=line) for line in text_lines(source.raw_text)]


def count_syllables(cells: Sequence[GridCellModel]) -> int:
    return sum(1 for cell in cells if not cell.rest)


def with_count(cells: list[GridCellModel], raw_text: str) -> GridLineModel:
    return GridLineModel(cells=cells, syllable_count=count_syllables(cells), raw_text=raw_text)


def _rest() -> GridCellModel:
    return GridCellModel(rest=True)


def fit_to_mode(
    lines: list[GridLineModel], grid_mode: GridMode, beats_per_line: int | None
) -> tuple[list[GridLineModel], int | None]:
    """In ``fixed`` mode pad every line with rests to ``beats_per_line``, raised to the longest
    line rather than truncating anything. ``auto`` returns the lines unchanged."""
    if grid_mode != GridMode.fixed:
        return lines, beats_per_line
    longest = max((len(line.cells) for line in lines), default=0)
    beats = max(beats_per_line or 0, longest)
    if beats == 0:
        return lines, beats_per_line
    padded = [
        line
        if len(line.cells) >= beats
        else line.model_copy(update={"cells": [*line.cells, *(_rest() for _ in range(beats - len(line.cells)))]})
        for line in lines
    ]
    return padded, beats


def union_tag_ids(sources: Sequence[MergeSource]) -> tuple[str, ...]:
    return tuple(dict.fromkeys(tag_id for source in sources for tag_id in source.tag_ids))


def _assemble(sources: Sequence[MergeSource], joined: list[GridLineModel]) -> MergedSnippet:
    first = sources[0]
    lines, beats_per_line = fit_to_mode(joined, first.grid_mode, first.beats_per_line)
    return MergedSnippet(
        raw_text="\n".join(line.raw_text for line in lines),
        grid=GridDataModel(lines=lines),
        snippet_type=first.snippet_type,
        grid_mode=first.grid_mode,
        beats_per_line=beats_per_line,
        group_id=first.group_id,
        position_x=first.position_x,
        position_y=first.position_y,
        tag_ids=union_tag_ids(sources),
        source_ids=tuple(source.id for source in sources),
    )


def _require_sources(sources: Sequence[MergeSource]) -> None:
    if not sources:
        raise TooFewSnippetsError("nothing to merge")


def merge_vertical(sources: Sequence[MergeSource]) -> MergedSnippet:
    """Stack the sources (already in merge order): every line of every source, in order."""
    _require_sources(sources)
    stacked = [
        with_count([cell.model_copy(deep=True) for cell in line.cells], line.raw_text)
        for source in sources
        for line in lines_of(source)
    ]
    return _assemble(sources, stacked)


def merge_horizontal(sources: Sequence[MergeSource]) -> MergedSnippet:
    """Join the sources (already in merge order) line by line.

    Result line i is line i of each source, cells concatenated with ``word_index`` re-based by
    the word count of the text already on the line (so words without cells still count), text
    joined with a single space. A source with no line i contributes nothing to it.
    """
    _require_sources(sources)
    per_source = [lines_of(source) for source in sources]
    line_count = max((len(lines) for lines in per_source), default=0)
    joined: list[GridLineModel] = []
    for i in range(line_count):
        cells: list[GridCellModel] = []
        parts: list[str] = []
        for lines in per_source:
            if i >= len(lines):
                continue
            line = lines[i]
            offset = word_count(" ".join(parts))
            for cell in line.cells:
                if isinstance(cell.word_index, int) and not isinstance(cell.word_index, bool):
                    cells.append(cell.model_copy(update={"word_index": cell.word_index + offset}, deep=True))
                else:
                    cells.append(cell.model_copy(deep=True))
            if line.raw_text.strip():
                parts.append(line.raw_text.strip())
        joined.append(with_count(cells, " ".join(parts)))
    return _assemble(sources, joined)


def merge_snippets(sources: Sequence[MergeSource], mode: MergeMode) -> MergedSnippet:
    """Order by canvas position for the mode, then merge. Needs two or more distinct sources;
    a repeated id counts once (first occurrence)."""
    distinct: dict[str, MergeSource] = {}
    for source in sources:
        distinct.setdefault(source.id, source)
    if len(distinct) < 2:
        raise TooFewSnippetsError("merge needs at least two distinct snippets")
    ordered = order_by_position(list(distinct.values()), mode)
    match mode:
        case MergeMode.vertical:
            return merge_vertical(ordered)
        case MergeMode.horizontal:
            return merge_horizontal(ordered)
