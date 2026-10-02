"""Builders for merge tests, mirroring the web client's former lib/merge/fixtures.ts."""

from __future__ import annotations

from app.models.snippet import GridMode, SnippetType
from app.schemas.snippet import GridCellModel, GridDataModel, GridLineModel
from app.services.merge_service import MergeSource


def cells_for(text: str) -> list[dict]:
    """Cells for a line of text: one syllable per word, "." is a rest."""
    cells: list[dict] = []
    word = 0
    for token in text.split():
        if token == ".":
            cells.append({"rest": True})
            continue
        cells.append({"syllable": token, "stress": 1, "word_index": word})
        word += 1
    return cells


def line(text: str, cell_text: str | None = None) -> dict:
    cells = cells_for(text if cell_text is None else cell_text)
    return {"cells": cells, "syllable_count": sum(1 for c in cells if not c.get("rest")), "raw_text": text}


def grid(*lines: dict) -> GridDataModel:
    return GridDataModel.model_validate({"lines": list(lines)})


_DEFAULT = object()


def source(
    id: str,
    text: str,
    *,
    grid_data: GridDataModel | None | object = _DEFAULT,
    snippet_type: SnippetType = SnippetType.verse,
    grid_mode: GridMode = GridMode.auto,
    beats_per_line: int | None = None,
    group_id: str | None = None,
    x: float = 0.0,
    y: float = 0.0,
    tags: tuple[str, ...] = (),
) -> MergeSource:
    """A merge source whose grid is one line per non-blank text line, one cell per word."""
    if grid_data is _DEFAULT:
        grid_data = grid(*(line(raw.strip()) for raw in text.split("\n") if raw.strip()))
    return MergeSource(
        id=id,
        raw_text=text,
        grid=grid_data,  # type: ignore[arg-type]
        snippet_type=snippet_type,
        grid_mode=grid_mode,
        beats_per_line=beats_per_line,
        group_id=group_id,
        position_x=x,
        position_y=y,
        tag_ids=tags,
    )


def dumped(lines: list[GridLineModel]) -> list[dict]:
    return [ln.model_dump(mode="json") for ln in lines]


def dumped_cells(cells: list[GridCellModel]) -> list[dict]:
    return [c.model_dump(mode="json") for c in cells]
