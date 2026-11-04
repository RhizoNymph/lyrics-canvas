"""HTTP mapping of the typed tag/preset/expression errors.

``detail = {"error": <code>, "message": <text>}``; an invalid expression adds
``"expression_error": {"code", "position", "message"}``.
"""

from __future__ import annotations

import uuid

from fastapi import HTTPException

from app.models.user import User
from app.schemas.tag import ExpressionErrorBody, TagErrorBody, TagErrorResponse
from app.services.tag_errors import InvalidTagExpressionError, TagServiceError
from app.services.tag_expr import TagExpressionError


def _expression_body(exc: TagExpressionError) -> ExpressionErrorBody:
    return ExpressionErrorBody(code=exc.code.value, position=exc.position, message=exc.message)


def tag_http_error(exc: TagServiceError) -> HTTPException:
    expression = _expression_body(exc.expression_error) if isinstance(exc, InvalidTagExpressionError) else None
    body = TagErrorBody(error=exc.code.value, message=exc.message, expression_error=expression)
    return HTTPException(status_code=exc.status, detail=body.model_dump(exclude_none=True))


def expression_http_error(exc: TagExpressionError) -> HTTPException:
    """A bad expression outside the tag routes (e.g. rhyme-suggest ``tag_filter``): 422."""
    return tag_http_error(InvalidTagExpressionError(exc))


def tag_errors(*statuses: int) -> dict[int | str, dict[str, object]]:
    return {status: {"model": TagErrorResponse} for status in statuses}


def owner_id(user: User) -> uuid.UUID:
    return user.id if isinstance(user.id, uuid.UUID) else uuid.UUID(str(user.id))
