"""SQL prefilter for search: one escaped ILIKE per term over the NFC-normalised column.

A term has no whitespace, so when it is a substring of the display text it lies inside one word
token, which appears verbatim in ``raw_text``: the prefilter never drops a real match. Callers
confirm every candidate with ``matching`` (display text, Python lowercasing) before counting it.
"""

from __future__ import annotations

from collections.abc import Sequence

from sqlalchemy import ColumnElement, and_, func, literal_column, true

LIKE_ESCAPE = "\\"


def like_pattern(term: str) -> str:
    escaped = term.replace(LIKE_ESCAPE, LIKE_ESCAPE * 2).replace("%", f"{LIKE_ESCAPE}%").replace("_", f"{LIKE_ESCAPE}_")
    return f"%{escaped}%"


def nfc(column: ColumnElement[str]) -> ColumnElement[str]:
    return func.normalize(column, literal_column("NFC"))


def terms_filter(column: ColumnElement[str], terms: Sequence[str]) -> ColumnElement[bool]:
    if not terms:
        return true()
    return and_(*(nfc(column).ilike(like_pattern(term), escape=LIKE_ESCAPE) for term in terms))
