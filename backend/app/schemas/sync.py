"""``GET /api/sync/snapshot`` response (docs/features/sync_versions.md#snapshot)."""

from __future__ import annotations

from pydantic import BaseModel

from app.models.snippet import GridMode, SnippetType
from app.schemas.canvas import CanvasResponse
from app.schemas.phonetics import DerivedFromModel, SnippetPhonetics
from app.schemas.snippet import GridDataModel
from app.schemas.tag import TagPresetResponse, TagResponse
from app.schemas.word_bank import BankWordResponse

# Responses always carry every field, so defaulted fields are required in the output schema.
_RESPONSE_CONFIG = {"json_schema_serialization_defaults_required": True}


class SyncPlacement(BaseModel):
    """One snippet's layout on one canvas (the per-canvas fields of ``SnippetResponse``)."""

    snippet_id: str
    canvas_id: str
    placement_id: str
    position_x: float
    position_y: float
    width: float
    height: float
    z_index: int
    group_id: str | None = None

    model_config = _RESPONSE_CONFIG


class SyncSnippet(BaseModel):
    """A snippet's shared content: ``SnippetResponse`` without the per-canvas fields
    (``placement_id, canvas_id, position_x, position_y, width, height, z_index, group_id,
    canvases``) and with ``tag_ids`` (sorted by lower-cased tag name, then id) for ``tags``."""

    id: str
    snippet_type: SnippetType
    grid_mode: GridMode
    beats_per_line: int | None = None
    grid_data: GridDataModel | None = None
    raw_text: str
    phonetics: SnippetPhonetics | None = None
    derived_from: DerivedFromModel | None = None
    created_at: str
    updated_at: str
    content_updated_at: str
    version: int
    tag_ids: list[str]

    model_config = _RESPONSE_CONFIG


class SyncSnapshotResponse(BaseModel):
    server_time: str  # the database clock the snapshot was read at, like the other timestamps
    canvases: list[CanvasResponse]
    placements: list[SyncPlacement]
    snippets: list[SyncSnippet]  # each snippet once, however many canvases hold it
    tags: list[TagResponse]
    tag_presets: list[TagPresetResponse]
    word_bank: list[BankWordResponse]

    model_config = _RESPONSE_CONFIG
