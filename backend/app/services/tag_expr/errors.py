"""Typed parse failures of the tag expression language (docs/features/tag_expressions.md)."""

from __future__ import annotations

from enum import StrEnum


class TagExprErrorCode(StrEnum):
    too_long = "too_long"
    unexpected_character = "unexpected_character"
    unterminated_string = "unterminated_string"
    invalid_escape = "invalid_escape"
    empty_name = "empty_name"
    unknown_type = "unknown_type"
    unexpected_token = "unexpected_token"
    unexpected_end = "unexpected_end"
    unclosed_paren = "unclosed_paren"
    empty_group = "empty_group"
    too_deep = "too_deep"


class TagExpressionError(Exception):
    """An invalid expression. ``position`` is a Unicode code point offset into the input."""

    def __init__(self, code: TagExprErrorCode, position: int, message: str) -> None:
        super().__init__(f"{code.value} at {position}: {message}")
        self.code = code
        self.position = position
        self.message = message

    def as_dict(self) -> dict[str, str | int]:
        """The wire shape: ``{"code", "position", "message"}``."""
        return {"code": self.code.value, "position": self.position, "message": self.message}
