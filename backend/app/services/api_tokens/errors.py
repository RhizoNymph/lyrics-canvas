"""Typed failures of the API token routes (docs/features/api_tokens.md#errors).

Each carries its wire code and HTTP status; app/api/tokens.py turns them into
``HTTPException(status, {"error": code, "message": message})``.
"""

from __future__ import annotations

from enum import StrEnum
from typing import ClassVar


class TokenErrorCode(StrEnum):
    invalid_token_name = "invalid_token_name"
    token_not_found = "token_not_found"
    token_management_forbidden = "token_management_forbidden"


class TokenError(Exception):
    code: ClassVar[TokenErrorCode]
    status: ClassVar[int]

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class InvalidTokenNameError(TokenError):
    """The name is empty after trimming or longer than 64 chars."""

    code = TokenErrorCode.invalid_token_name
    status = 422


class TokenNotFoundError(TokenError):
    """No live (not revoked) token with that id belongs to the caller."""

    code = TokenErrorCode.token_not_found
    status = 404


class TokenManagementForbiddenError(TokenError):
    """The request was authenticated with an API token; managing tokens needs a login JWT."""

    code = TokenErrorCode.token_management_forbidden
    status = 403
