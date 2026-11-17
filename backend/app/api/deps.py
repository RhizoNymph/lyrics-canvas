"""Request authentication (docs/features/api_tokens.md#authentication).

A bearer starting with ``lct_`` is a personal API token, looked up by its sha256; anything else is a
login JWT, decoded as before. Both give the same ``User``; ``get_auth`` also says which one was used,
so the token management routes can refuse API tokens (``require_login``).
"""

import uuid
from dataclasses import dataclass
from enum import StrEnum

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.models.user import User
from app.schemas.api_token import ApiTokenErrorBody
from app.services.api_tokens import service as api_tokens
from app.services.api_tokens.errors import TokenManagementForbiddenError
from app.services.api_tokens.secrets import is_api_token
from app.services.auth_service import decode_access_token, get_user_by_id

security = HTTPBearer()


class AuthMethod(StrEnum):
    login = "login"  # a JWT from /api/auth
    api_token = "api_token"  # a personal API token (lct_...)


@dataclass(frozen=True, slots=True)
class Auth:
    user: User
    method: AuthMethod
    token_id: uuid.UUID | None = None  # set for AuthMethod.api_token


def _unauthorized(detail: str) -> HTTPException:
    return HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=detail)


async def get_auth(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: AsyncSession = Depends(get_db),
) -> Auth:
    bearer = credentials.credentials
    if is_api_token(bearer):
        found = await api_tokens.authenticate(db, bearer)
        if found is None:
            raise _unauthorized("Invalid token")
        return Auth(user=found.user, method=AuthMethod.api_token, token_id=found.token_id)
    user_id = decode_access_token(bearer)
    if not user_id:
        raise _unauthorized("Invalid token")
    user = await get_user_by_id(db, user_id)
    if not user:
        raise _unauthorized("User not found")
    return Auth(user=user, method=AuthMethod.login)


async def get_current_user(auth: Auth = Depends(get_auth)) -> User:
    return auth.user


async def require_login(auth: Auth = Depends(get_auth)) -> User:
    """The user of a login JWT; 403 ``token_management_forbidden`` for an API token."""
    if auth.method is AuthMethod.api_token:
        exc = TokenManagementForbiddenError("managing API tokens needs a login session, not an API token")
        body = ApiTokenErrorBody(error=exc.code.value, message=exc.message)
        raise HTTPException(status_code=exc.status, detail=body.model_dump())
    return auth.user
