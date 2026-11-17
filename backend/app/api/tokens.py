"""``/api/tokens``: personal API tokens (docs/features/api_tokens.md#api).

Every route needs a login JWT (``require_login``): a request authenticated with an API token gets
403 ``token_management_forbidden``, so a leaked token cannot mint or revoke tokens.
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import require_login
from app.api.tag_errors import owner_id
from app.db.database import get_db
from app.models.api_token import ApiToken
from app.models.user import User
from app.schemas.api_token import ApiTokenCreate, ApiTokenCreatedResponse, ApiTokenErrorBody, ApiTokenErrorResponse, ApiTokenResponse
from app.services.api_tokens import service as api_tokens
from app.services.api_tokens.errors import TokenError

router = APIRouter(prefix="/api/tokens", tags=["tokens"])

_FORBIDDEN = {403: {"model": ApiTokenErrorResponse}}


def token_http_error(exc: TokenError) -> HTTPException:
    return HTTPException(status_code=exc.status, detail=ApiTokenErrorBody(error=exc.code.value, message=exc.message).model_dump())


def _optional(value: object) -> str | None:
    return None if value is None else str(value)


def _token_response(token: ApiToken) -> ApiTokenResponse:
    return ApiTokenResponse(
        id=str(token.id),
        name=token.name,
        prefix=token.prefix,
        created_at=str(token.created_at),
        last_used_at=_optional(token.last_used_at),
        expires_at=_optional(token.expires_at),
    )


@router.post("", response_model=ApiTokenCreatedResponse, status_code=201, responses={**_FORBIDDEN, 422: {"model": ApiTokenErrorResponse}})
async def create_token(req: ApiTokenCreate, user: User = Depends(require_login), db: AsyncSession = Depends(get_db)):
    """A new token; ``secret`` is the only time the full token is returned."""
    try:
        created = await api_tokens.create_token(db, owner_id(user), req.name, req.expires_in_days)
    except TokenError as exc:
        raise token_http_error(exc) from exc
    return ApiTokenCreatedResponse(token=_token_response(created.token), secret=created.secret)


@router.get("", response_model=list[ApiTokenResponse], responses=_FORBIDDEN)
async def list_tokens(user: User = Depends(require_login), db: AsyncSession = Depends(get_db)):
    """The caller's tokens that are not revoked (expired ones included), newest first; no secrets."""
    return [_token_response(t) for t in await api_tokens.list_tokens(db, owner_id(user))]


@router.delete("/{token_id}", status_code=204, responses={**_FORBIDDEN, 404: {"model": ApiTokenErrorResponse}})
async def revoke_token(token_id: uuid.UUID, user: User = Depends(require_login), db: AsyncSession = Depends(get_db)):
    """Revoke: the token stops working at once and leaves the list. 404 ``token_not_found`` when it is
    not the caller's or already revoked."""
    try:
        await api_tokens.revoke_token(db, owner_id(user), token_id)
    except TokenError as exc:
        raise token_http_error(exc) from exc
    return Response(status_code=204)
