from pydantic import BaseModel, ConfigDict

from app.models.snippet import SnippetType, GridMode
from app.schemas.phonetics import DerivedFromModel, SnippetPhonetics


class GridCellModel(BaseModel):
    """One beat of a grid line. Extra keys are kept so client-side annotations survive."""

    model_config = ConfigDict(extra="allow")

    syllable: str | None = None
    stress: int | None = None
    word_index: int | None = None
    rest: bool | None = None


class GridLineModel(BaseModel):
    model_config = ConfigDict(extra="allow")

    cells: list[GridCellModel]
    syllable_count: int = 0
    raw_text: str = ""


class GridDataModel(BaseModel):
    """A client-supplied beat grid: ``{"lines": [{"cells": [...]}, ...]}``."""

    model_config = ConfigDict(extra="allow")

    lines: list[GridLineModel]


class SnippetCreate(BaseModel):
    snippet_type: SnippetType = SnippetType.freestyle
    grid_mode: GridMode = GridMode.auto
    beats_per_line: int | None = None
    raw_text: str = ""
    # When given, stored as-is (no re-syllabification); rests and stress are preserved.
    grid_data: GridDataModel | None = None
    position_x: float = 0.0
    position_y: float = 0.0
    width: float = 300.0
    height: float = 200.0
    group_id: str | None = None
    derived_from: DerivedFromModel | None = None


class SnippetUpdate(BaseModel):
    snippet_type: SnippetType | None = None
    grid_mode: GridMode | None = None
    beats_per_line: int | None = None
    grid_data: dict | None = None
    raw_text: str | None = None
    position_x: float | None = None
    position_y: float | None = None
    width: float | None = None
    height: float | None = None
    z_index: int | None = None
    group_id: str | None = None


class BatchPositionUpdate(BaseModel):
    updates: list[dict]  # [{"id": "...", "position_x": ..., "position_y": ...}]


class SnippetResponse(BaseModel):
    id: str
    canvas_id: str
    snippet_type: SnippetType
    grid_mode: GridMode
    beats_per_line: int | None = None
    grid_data: dict | None = None
    raw_text: str
    position_x: float
    position_y: float
    width: float
    height: float
    z_index: int
    group_id: str | None = None
    tags: list = []
    phonetics: SnippetPhonetics | None = None
    derived_from: DerivedFromModel | None = None
    created_at: str
    updated_at: str

    model_config = {"from_attributes": True}
