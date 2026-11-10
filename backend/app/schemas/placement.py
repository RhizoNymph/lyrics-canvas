"""Request/response models for placements: remove from canvas, transfer, and the typed error body
(docs/features/snippet_placements.md#api)."""

from __future__ import annotations

import uuid

from pydantic import BaseModel, Field

from app.services.placements.transfer import TransferMode

MAX_TRANSFER_SNIPPETS = 500


class PlacementErrorBody(BaseModel):
    error: str  # a PlacementErrorCode value
    message: str


class PlacementErrorResponse(BaseModel):
    detail: PlacementErrorBody


class RemovePlacementResponse(BaseModel):
    snippet_deleted: bool  # true when that was the snippet's last canvas and it is gone


class TransferRequest(BaseModel):
    snippet_ids: list[uuid.UUID] = Field(min_length=1, max_length=MAX_TRANSFER_SNIPPETS)
    target_canvas_id: uuid.UUID
    mode: TransferMode


class TransferResponse(BaseModel):
    target_canvas_id: str
    placed: list[str]
    already_on_target: list[str]
    removed_from_source: bool
