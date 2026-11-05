"""Typed tag and preset failures (docs/features/tag_expressions.md). Each carries the wire error
code and HTTP status; the API turns them into
``HTTPException(status, {"error": code, "message": message[, "expression_error": {...}]})``."""

from __future__ import annotations

from enum import StrEnum
from typing import ClassVar

from app.services.tag_expr import TagExpressionError


class TagErrorCode(StrEnum):
    tag_not_found = "tag_not_found"
    snippet_not_found = "snippet_not_found"
    tag_name_taken = "tag_name_taken"
    invalid_tag_name = "invalid_tag_name"
    invalid_color = "invalid_color"
    preset_not_found = "preset_not_found"
    preset_name_taken = "preset_name_taken"
    invalid_preset_name = "invalid_preset_name"
    empty_preset = "empty_preset"
    invalid_tag_expression = "invalid_tag_expression"
    preset_expression_too_long = "preset_expression_too_long"
    # word bank (app/services/word_bank/errors.py)
    invalid_word = "invalid_word"
    word_exists = "word_exists"
    word_not_found = "word_not_found"
    too_many_words = "too_many_words"
    related_words_warming = "related_words_warming"
    related_words_unavailable = "related_words_unavailable"


class TagServiceError(Exception):
    code: ClassVar[TagErrorCode]
    status: ClassVar[int]

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class TagNotFoundError(TagServiceError):
    code = TagErrorCode.tag_not_found
    status = 404


class SnippetNotFoundError(TagServiceError):
    code = TagErrorCode.snippet_not_found
    status = 404


class TagNameTakenError(TagServiceError):
    code = TagErrorCode.tag_name_taken
    status = 409


class InvalidTagNameError(TagServiceError):
    code = TagErrorCode.invalid_tag_name
    status = 422


class InvalidColorError(TagServiceError):
    code = TagErrorCode.invalid_color
    status = 422


class PresetNotFoundError(TagServiceError):
    code = TagErrorCode.preset_not_found
    status = 404


class PresetNameTakenError(TagServiceError):
    code = TagErrorCode.preset_name_taken
    status = 409


class InvalidPresetNameError(TagServiceError):
    code = TagErrorCode.invalid_preset_name
    status = 422


class EmptyPresetError(TagServiceError):
    code = TagErrorCode.empty_preset
    status = 422


class InvalidTagExpressionError(TagServiceError):
    """The expression does not parse; ``expression_error`` says where and why."""

    code = TagErrorCode.invalid_tag_expression
    status = 422

    def __init__(self, expression_error: TagExpressionError) -> None:
        super().__init__(f"invalid tag expression: {expression_error.message}")
        self.expression_error = expression_error


class PresetExpressionTooLongError(TagServiceError):
    """A tag rename would push a preset's expression past the length limit; nothing changed."""

    code = TagErrorCode.preset_expression_too_long
    status = 409
