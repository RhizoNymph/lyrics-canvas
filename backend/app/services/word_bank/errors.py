"""Typed word bank failures. They extend TagServiceError, so the API maps them through the same
``{"error": code, "message": text}`` body; ``invalid_word`` in a batch adds ``"index"`` and the
``related_words_warming`` response carries a ``Retry-After`` header (app/api/word_bank_errors.py)."""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.services.tag_errors import TagErrorCode, TagServiceError

if TYPE_CHECKING:
    from app.services.word_bank.text import WordProblem

RETRY_AFTER_SECONDS = 10


class WordBankError(TagServiceError):
    """Base of the word bank failures."""


class InvalidWordError(WordBankError):
    """The text breaks the word rules. ``index`` is the first bad position of a batch (else None)."""

    code = TagErrorCode.invalid_word
    status = 422

    def __init__(self, problem: WordProblem, index: int | None = None) -> None:
        message = problem.message if index is None else f"word {index}: {problem.message}"
        super().__init__(message)
        self.problem = problem
        self.index = index


class WordExistsError(WordBankError):
    code = TagErrorCode.word_exists
    status = 409


class WordNotFoundError(WordBankError):
    code = TagErrorCode.word_not_found
    status = 404


class TooManyWordsError(WordBankError):
    code = TagErrorCode.too_many_words
    status = 422


class RelatedWordsWarmingError(WordBankError):
    """The related-word index is still being built; retry after ``retry_after`` seconds."""

    code = TagErrorCode.related_words_warming
    status = 503
    retry_after = RETRY_AFTER_SECONDS


class RelatedWordsUnavailableError(WordBankError):
    """The embedding model cannot be loaded (or the index build failed)."""

    code = TagErrorCode.related_words_unavailable
    status = 503
