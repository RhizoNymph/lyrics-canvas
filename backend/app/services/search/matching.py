"""The search matching rule shared by backend, web and Android (rest_mark_vectors.json ``match``).

The query is NFC-normalised, trimmed, split on whitespace into terms and lowercased. A text matches
when every term is a substring of its display text (rest marks removed), NFC-normalised and
lowercased. No terms match everything.
"""

from __future__ import annotations

import unicodedata
from collections.abc import Sequence

from app.services.rest_marks import display_text


def normalize_text(text: str) -> str:
    return unicodedata.normalize("NFC", text).lower()


def normalize_terms(query: str) -> tuple[str, ...]:
    return tuple(term.lower() for term in unicodedata.normalize("NFC", query).strip().split())


def _contains_all(terms: Sequence[str], haystack: str) -> bool:
    return all(term in haystack for term in terms)


def matches_terms(terms: Sequence[str], raw_text: str) -> bool:
    """Snippet text: terms against the display text."""
    return _contains_all(terms, normalize_text(display_text(raw_text)))


def title_matches(terms: Sequence[str], title: str) -> bool:
    """Canvas titles have no rest marks: terms against the title as is."""
    return _contains_all(terms, normalize_text(title))


def matches_query(query: str, raw_text: str) -> bool:
    return matches_terms(normalize_terms(query), raw_text)
