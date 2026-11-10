from pydantic import BaseModel

from app.schemas.group import GroupResponse
from app.schemas.snippet import SnippetResponse


class CanvasCreate(BaseModel):
    # stripped; empty falls back to "Untitled Canvas"; over 255 characters is invalid_canvas_title
    title: str = "Untitled Canvas"


class CanvasUpdate(BaseModel):
    # stripped; must then be 1-255 characters, else invalid_canvas_title
    title: str | None = None
    is_public: bool | None = None
    viewport_state: dict | None = None


class CanvasResponse(BaseModel):
    id: str
    owner_id: str
    title: str
    is_public: bool
    share_token: str | None = None
    viewport_state: dict | None = None
    created_at: str
    updated_at: str

    # Responses always carry every field, so defaulted fields are required in the output schema.
    model_config = {"from_attributes": True, "json_schema_serialization_defaults_required": True}


class CanvasDetailResponse(CanvasResponse):
    snippets: list[SnippetResponse] = []
    groups: list[GroupResponse] = []


class ShareResponse(BaseModel):
    share_token: str
