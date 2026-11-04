"""Validated tag/preset values. Constructors raise the typed TagServiceError subclasses, so the
services only ever see a trimmed name, a ``#rrggbb`` color and a normalized, non-empty expression."""

from __future__ import annotations

import re
import unicodedata
from typing import NewType

from app.services.tag_errors import (
    EmptyPresetError,
    InvalidColorError,
    InvalidPresetNameError,
    InvalidTagExpressionError,
    InvalidTagNameError,
)
from app.services.tag_expr import MAX_LENGTH, TagExpressionError, TagExprErrorCode, normalize

TagName = NewType("TagName", str)
PresetName = NewType("PresetName", str)
TagColor = NewType("TagColor", str)
PresetExpression = NewType("PresetExpression", str)  # normalized, non-empty, at most MAX_LENGTH

MAX_NAME_LENGTH = 100  # code points, after trimming
_COLOR = re.compile(r"#[0-9a-fA-F]{6}")


def _name_problem(raw: str) -> tuple[str, str | None]:
    """The trimmed name and what is wrong with it (None when valid)."""
    name = raw.strip()
    if not name:
        return name, "a name must not be empty"
    if len(name) > MAX_NAME_LENGTH:
        return name, f"a name is at most {MAX_NAME_LENGTH} characters"
    if any(unicodedata.category(c) == "Cc" for c in name):
        return name, "a name must not contain control characters"
    return name, None


def tag_name(raw: str) -> TagName:
    name, problem = _name_problem(raw)
    if problem is not None:
        raise InvalidTagNameError(problem)
    return TagName(name)


def preset_name(raw: str) -> PresetName:
    name, problem = _name_problem(raw)
    if problem is not None:
        raise InvalidPresetNameError(problem)
    return PresetName(name)


def tag_color(raw: str) -> TagColor:
    """``#rrggbb`` (hex digits in any case), stored lowercase."""
    if not _COLOR.fullmatch(raw):
        raise InvalidColorError("a color is #rrggbb")
    return TagColor(raw.lower())


def preset_expression(raw: str) -> PresetExpression:
    """The normalized expression. Empty is refused (it would match everything), and so is a
    normal form longer than the parser accepts (``a b`` grows to ``a & b``)."""
    try:
        text = normalize(raw)
    except TagExpressionError as exc:
        raise InvalidTagExpressionError(exc) from exc
    if not text:
        raise EmptyPresetError("a preset needs a non-empty expression")
    if len(text) > MAX_LENGTH:
        raise InvalidTagExpressionError(
            TagExpressionError(
                TagExprErrorCode.too_long, MAX_LENGTH, f"the normalized expression is longer than {MAX_LENGTH} characters"
            )
        )
    return PresetExpression(text)
