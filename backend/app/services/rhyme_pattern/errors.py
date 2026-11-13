"""Typed errors of the rhyme-pattern service. All are 422 with
``detail = {"error": <code>, "message": <text>}`` (mapped in ``app/api/nlp.py``)."""

from __future__ import annotations

from enum import StrEnum
from typing import ClassVar


class PatternErrorCode(StrEnum):
    range_invalid = "pattern_range_invalid"
    span_invalid = "pattern_span_invalid"
    slot_order = "pattern_slot_order"


class PatternError(Exception):
    code: ClassVar[PatternErrorCode]
    status: ClassVar[int] = 422

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class PatternRangeInvalidError(PatternError):
    """The line range is outside the snippet, has fewer than 2 lines with words, or more than 16 lines."""

    code = PatternErrorCode.range_invalid


class PatternSpanInvalidError(PatternError):
    """A member is outside the range or its line, empty, unpronounceable, shares its line with
    another member of the slot, or overlaps a member of another slot."""

    code = PatternErrorCode.span_invalid


class PatternSlotOrderError(PatternError):
    """The slot order disagrees with word order on some line."""

    code = PatternErrorCode.slot_order
