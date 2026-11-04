"""Tag and preset request/response models (docs/features/tag_expressions.md#http-api).

Names, colors and expressions are plain strings here; the routes validate them through
app/services/tag_values.py so failures carry the contract's error codes instead of pydantic's.
"""

from pydantic import BaseModel

from app.models.tag import DEFAULT_TAG_COLOR

# Responses always carry every field, so defaulted fields are required in the output schema.
_RESPONSE_CONFIG = {"from_attributes": True, "json_schema_serialization_defaults_required": True}


class TagCreate(BaseModel):
    name: str
    color: str = DEFAULT_TAG_COLOR


class TagUpdate(BaseModel):
    name: str | None = None
    color: str | None = None


class TagResponse(BaseModel):
    """A tag of the user's tag list (``GET /api/tags`` and tag writes)."""

    id: str
    name: str
    color: str
    snippet_count: int
    created_at: str

    model_config = _RESPONSE_CONFIG


class SnippetTagResponse(BaseModel):
    """A tag as embedded in a snippet (``SnippetResponse.tags``)."""

    id: str
    name: str
    color: str

    model_config = _RESPONSE_CONFIG


class TagLinkResponse(BaseModel):
    ok: bool = True

    model_config = {"json_schema_serialization_defaults_required": True}


class TagPresetCreate(BaseModel):
    name: str
    expression: str


class TagPresetUpdate(BaseModel):
    name: str | None = None
    expression: str | None = None


class TagPresetResponse(BaseModel):
    id: str
    name: str
    expression: str  # normalized
    created_at: str
    updated_at: str

    model_config = _RESPONSE_CONFIG


class ExpressionErrorBody(BaseModel):
    code: str  # a TagExprErrorCode value
    position: int  # Unicode code point offset into the submitted expression
    message: str


class TagErrorBody(BaseModel):
    error: str  # a TagErrorCode value
    message: str
    expression_error: ExpressionErrorBody | None = None  # only for invalid_tag_expression


class TagErrorResponse(BaseModel):
    detail: TagErrorBody
