"""Typed search failures; app/api/search.py turns them into
``HTTPException(status, {"error": code, "message": message})``."""

from __future__ import annotations

from enum import StrEnum
from typing import ClassVar


class SearchErrorCode(StrEnum):
    invalid_search_query = "invalid_search_query"


class SearchError(Exception):
    code: ClassVar[SearchErrorCode]
    status: ClassVar[int]

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class InvalidSearchQueryError(SearchError):
    """``q`` is missing, blank, or longer than the limit after NFC and trimming."""

    code = SearchErrorCode.invalid_search_query
    status = 422
