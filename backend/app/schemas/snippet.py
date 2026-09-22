from pydantic import BaseModel

from app.models.snippet import SnippetType, GridMode
from app.schemas.phonetics import DerivedFromModel, SnippetPhonetics


class SnippetCreate(BaseModel):
    snippet_type: SnippetType = SnippetType.freestyle
    grid_mode: GridMode = GridMode.auto
    beats_per_line: int | None = None
    raw_text: str = ""
    position_x: float = 0.0
    position_y: float = 0.0
    width: float = 300.0
    height: float = 200.0
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
