"""Word bank text rules (docs/features/word_bank.md#word-text-rules). Pure.

Normalize: Unicode NFC, strip the ends, collapse every internal whitespace run to one space.
Valid after normalizing: 1-64 code points, at most 4 space-separated words, only letters
(categories ``L*``), apostrophes (``'`` and ``’``), hyphens (``-``) and spaces, at least one
letter. Case is kept; uniqueness (in the service) ignores case.
"""

from __future__ import annotations

import unicodedata
from collections.abc import Sequence
from enum import Enum
from typing import NewType

from app.models.word_bank import MAX_WORD_LENGTH
from app.services.word_bank.errors import InvalidWordError

WordText = NewType("WordText", str)  # normalized and valid
RelatedQuery = NewType("RelatedQuery", str)  # stripped, 1..MAX_QUERY_LENGTH code points

MAX_WORDS = 4
MAX_QUERY_LENGTH = 64
PUNCTUATION = frozenset("'’-")


class WordProblem(Enum):
    """Why a normalized text is not a valid word; ``message`` is the human text."""

    empty = "a word must not be empty"
    too_long = f"a word is at most {MAX_WORD_LENGTH} characters"
    too_many_words = f"a phrase is at most {MAX_WORDS} words"
    invalid_character = "only letters, apostrophes, hyphens and spaces are allowed"
    no_letter = "a word needs at least one letter"
    # related-word queries (any text, only the length is checked)
    query_empty = "a related-word query must not be empty"
    query_too_long = f"a related-word query is at most {MAX_QUERY_LENGTH} characters"

    @property
    def message(self) -> str:
        return self.value


def normalize_word(raw: str) -> str:
    """NFC, then trimmed with internal whitespace runs collapsed to single spaces."""
    return " ".join(unicodedata.normalize("NFC", raw).split())


def _is_letter(c: str) -> bool:
    return unicodedata.category(c).startswith("L")


def word_problem(text: str) -> WordProblem | None:
    """What is wrong with an already normalized text (None when valid). Checked in this order:
    empty, too long, too many words, a disallowed character, no letter."""
    if not text:
        return WordProblem.empty
    if len(text) > MAX_WORD_LENGTH:
        return WordProblem.too_long
    if len(text.split(" ")) > MAX_WORDS:
        return WordProblem.too_many_words
    if any(not (c == " " or c in PUNCTUATION or _is_letter(c)) for c in text):
        return WordProblem.invalid_character
    if not any(_is_letter(c) for c in text):
        return WordProblem.no_letter
    return None


def word_text(raw: str) -> WordText:
    """The normalized text; raises InvalidWordError."""
    text = normalize_word(raw)
    problem = word_problem(text)
    if problem is not None:
        raise InvalidWordError(problem)
    return WordText(text)


def word_texts(raws: Sequence[str]) -> list[WordText]:
    """Every text normalized, in order; the first bad one raises InvalidWordError with its index."""
    out: list[WordText] = []
    for index, raw in enumerate(raws):
        text = normalize_word(raw)
        problem = word_problem(text)
        if problem is not None:
            raise InvalidWordError(problem, index)
        out.append(WordText(text))
    return out


def related_query(raw: str) -> RelatedQuery:
    """A related-word query: any text (a word, phrase or theme), 1-64 code points after a strip.
    Raises InvalidWordError."""
    text = raw.strip()
    if not text:
        raise InvalidWordError(WordProblem.query_empty)
    if len(text) > MAX_QUERY_LENGTH:
        raise InvalidWordError(WordProblem.query_too_long)
    return RelatedQuery(text)
