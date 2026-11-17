"""Request/response models of ``/api/tokens`` (docs/features/api_tokens.md#api)."""

from __future__ import annotations

from pydantic import BaseModel, Field

from app.services.api_tokens.values import DEFAULT_EXPIRES_IN_DAYS, MAX_EXPIRES_IN_DAYS, MIN_EXPIRES_IN_DAYS

_RESPONSE_CONFIG = {"json_schema_serialization_defaults_required": True}


class ApiTokenCreate(BaseModel):
    name: str  # trimmed; 1-64 chars, else 422 invalid_token_name
    # omitted = 90 days; null = never expires
    expires_in_days: int | None = Field(default=DEFAULT_EXPIRES_IN_DAYS, ge=MIN_EXPIRES_IN_DAYS, le=MAX_EXPIRES_IN_DAYS)


class ApiTokenResponse(BaseModel):
    """A token without its secret. Timestamps are ``str(datetime)`` like the rest of the API."""

    id: str
    name: str
    prefix: str  # the first 12 chars of the token ("lct_" + 8)
    created_at: str
    last_used_at: str | None = None
    expires_at: str | None = None  # null = never

    model_config = _RESPONSE_CONFIG


class ApiTokenCreatedResponse(BaseModel):
    token: ApiTokenResponse
    secret: str  # the full token; shown once, never retrievable again

    model_config = _RESPONSE_CONFIG


class ApiTokenErrorBody(BaseModel):
    error: str  # a TokenErrorCode value
    message: str


class ApiTokenErrorResponse(BaseModel):
    detail: ApiTokenErrorBody
