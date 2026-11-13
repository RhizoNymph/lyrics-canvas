"""Request/response models for POST /api/canvases/{canvas_id}/merge."""

from __future__ import annotations

import enum
import uuid

from pydantic import BaseModel, field_validator

from app.schemas.snippet import SnippetResponse
from app.schemas.versioning import BaseVersions, ClientId


class MergeMode(str, enum.Enum):
    vertical = "vertical"  # stacked: every line of every block, top to bottom
    horizontal = "horizontal"  # joined: line i of every block, left to right


class MergeRequest(BaseModel):
    """``snippet_ids`` are de-duplicated (first occurrence kept); at least two must remain."""

    snippet_ids: list[uuid.UUID]
    mode: MergeMode
    merged_id: ClientId | None = None  # client-chosen id of the merged snippet
    base_versions: BaseVersions | None = None  # {original id: version}

    @field_validator("snippet_ids")
    @classmethod
    def _two_or_more_distinct(cls, ids: list[uuid.UUID]) -> list[uuid.UUID]:
        distinct = list(dict.fromkeys(ids))
        if len(distinct) < 2:
            raise ValueError("merge needs at least two distinct snippet ids")
        return distinct


class MergeResponse(BaseModel):
    snippet: SnippetResponse
    deleted_ids: list[str]
