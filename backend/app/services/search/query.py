"""A validated search query for ``GET /api/search``: 1-200 code points after NFC and trimming."""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass

from app.services.search.errors import InvalidSearchQueryError
from app.services.search.matching import normalize_terms

MAX_QUERY_LENGTH = 200


@dataclass(frozen=True, slots=True)
class SearchQuery:
    text: str  # NFC, trimmed, 1..MAX_QUERY_LENGTH code points
    terms: tuple[str, ...]  # never empty

    def __post_init__(self) -> None:
        if not self.terms:
            raise ValueError("a search query has at least one term")


def parse_search_query(raw: str | None) -> SearchQuery:
    if raw is None:
        raise InvalidSearchQueryError("q is required")
    text = unicodedata.normalize("NFC", raw).strip()
    if not text:
        raise InvalidSearchQueryError("q must not be blank")
    if len(text) > MAX_QUERY_LENGTH:
        raise InvalidSearchQueryError(f"q must be at most {MAX_QUERY_LENGTH} characters after trimming")
    return SearchQuery(text=text, terms=normalize_terms(text))
