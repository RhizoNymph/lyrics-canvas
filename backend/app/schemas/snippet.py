import uuid

from pydantic import BaseModel, ConfigDict, SerializerFunctionWrapHandler, model_serializer

from app.models.snippet import SnippetType, GridMode
from app.schemas.phonetics import DerivedFromModel, SnippetPhonetics
from app.schemas.tag import SnippetTagResponse


class FaithfulModel(BaseModel):
    """A permissive model that serializes only the keys it was given (plus extras).

    Grids are stored and returned verbatim: a rest cell ``{"rest": true}`` must not grow
    ``"syllable": null`` etc. on the way out, so unset optional fields are never emitted.
    """

    # Validation and serialization schemas are identical (no field is guaranteed on output),
    # so pin one mode and the OpenAPI document gets a single component per grid model.
    model_config = ConfigDict(extra="allow", json_schema_mode_override="validation")

    # No return annotation on purpose: an annotated return type would replace the model's
    # serialization JSON schema with that type's.
    @model_serializer(mode="wrap")
    def _set_fields_only(self, handler: SerializerFunctionWrapHandler):
        data = handler(self)
        keep = self.model_fields_set | set(self.model_extra or {})
        return {key: value for key, value in data.items() if key in keep}


class GridCellModel(FaithfulModel):
    """One beat of a grid line. Extra keys are kept so client-side annotations survive."""

    syllable: str | None = None
    stress: int | None = None
    word_index: int | None = None
    rest: bool | None = None


class GridLineModel(FaithfulModel):
    cells: list[GridCellModel]
    syllable_count: int = 0
    raw_text: str = ""


class GridDataModel(FaithfulModel):
    """A beat grid: ``{"lines": [{"cells": [...]}, ...]}``."""

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
    grid_data: GridDataModel | None = None
    raw_text: str | None = None
    position_x: float | None = None
    position_y: float | None = None
    width: float | None = None
    height: float | None = None
    z_index: int | None = None
    group_id: str | None = None


class BatchPositionItem(BaseModel):
    id: uuid.UUID
    position_x: float | None = None
    position_y: float | None = None


class BatchPositionUpdate(BaseModel):
    """Positions of placements on ``canvas_id`` (required; applies to every update)."""

    canvas_id: uuid.UUID
    updates: list[BatchPositionItem]


class BatchPositionResult(BaseModel):
    updated: int


class SnippetCanvasRef(BaseModel):
    id: str
    title: str


class SnippetResponse(BaseModel):
    """A snippet in the context of one canvas: content is shared by every canvas holding it;
    ``canvas_id, position_x, position_y, width, height, z_index, group_id`` and ``placement_id``
    are that canvas's placement. ``canvases`` lists every canvas holding the snippet (including
    this one), sorted by lower-cased title, then id."""

    id: str
    placement_id: str
    canvas_id: str
    snippet_type: SnippetType
    grid_mode: GridMode
    beats_per_line: int | None = None
    grid_data: GridDataModel | None = None
    raw_text: str
    position_x: float
    position_y: float
    width: float
    height: float
    z_index: int
    group_id: str | None = None
    tags: list[SnippetTagResponse] = []
    phonetics: SnippetPhonetics | None = None
    derived_from: DerivedFromModel | None = None
    created_at: str
    updated_at: str
    # Last content edit (text, type, grid); moves, resizes and tags do not change it.
    content_updated_at: str
    canvases: list[SnippetCanvasRef] = []

    # Responses always carry every field, so defaulted fields are required in the output schema.
    model_config = {"from_attributes": True, "json_schema_serialization_defaults_required": True}
