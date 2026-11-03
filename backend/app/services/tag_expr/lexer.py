"""Tokenizer. Runs over the whole input before parsing, so lexer errors win over parse errors."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from app.models.snippet import SnippetType
from app.services.tag_expr.errors import TagExpressionError, TagExprErrorCode

MAX_LENGTH = 500  # code points

TYPE_PREFIX = "type:"
QUOTE = '"'
BACKSLASH = "\\"


class Op(StrEnum):
    lparen = "("
    rparen = ")"
    and_ = "&"
    or_ = "|"
    not_ = "!"


KEYWORDS: dict[str, Op] = {"and": Op.and_, "or": Op.or_, "not": Op.not_}
OPERATOR_CHARS = frozenset(op.value for op in Op)
TYPES: dict[str, SnippetType] = {t.value: t for t in SnippetType}


@dataclass(frozen=True, slots=True)
class OpToken:
    op: Op
    position: int


@dataclass(frozen=True, slots=True)
class TagToken:
    name: str
    position: int


@dataclass(frozen=True, slots=True)
class TypeToken:
    value: SnippetType
    position: int


Token = OpToken | TagToken | TypeToken


def is_word_char(c: str) -> bool:
    return not c.isspace() and c not in OPERATOR_CHARS and c != QUOTE and c != BACKSLASH


def tokenize(text: str) -> list[Token]:
    if len(text) > MAX_LENGTH:
        raise TagExpressionError(TagExprErrorCode.too_long, MAX_LENGTH, f"expressions are limited to {MAX_LENGTH} characters")
    tokens: list[Token] = []
    i = 0
    while i < len(text):
        c = text[i]
        if c.isspace():
            i += 1
        elif c in OPERATOR_CHARS:
            tokens.append(OpToken(Op(c), i))
            i += 1
        elif c == QUOTE:
            token, i = _quoted(text, i)
            tokens.append(token)
        elif c == BACKSLASH:
            raise TagExpressionError(TagExprErrorCode.unexpected_character, i, "a backslash is only allowed inside quotes")
        else:
            start = i
            while i < len(text) and is_word_char(text[i]):
                i += 1
            tokens.append(_word(text[start:i], start))
    return tokens


def _quoted(text: str, start: int) -> tuple[TagToken, int]:
    """The quoted name opening at ``start`` and the index after its closing quote."""
    buf: list[str] = []
    i = start + 1
    while True:
        if i >= len(text):
            raise TagExpressionError(TagExprErrorCode.unterminated_string, start, "missing closing quote")
        c = text[i]
        if c == BACKSLASH:
            if i + 1 < len(text) and text[i + 1] in (QUOTE, BACKSLASH):
                buf.append(text[i + 1])
                i += 2
                continue
            raise TagExpressionError(TagExprErrorCode.invalid_escape, i, 'only \\" and \\\\ are valid escapes')
        if c == QUOTE:
            break
        buf.append(c)
        i += 1
    name = "".join(buf)
    if not name.strip():
        raise TagExpressionError(TagExprErrorCode.empty_name, start, "a quoted tag name must not be empty")
    return TagToken(name, start), i + 1


def _word(word: str, start: int) -> Token:
    lowered = word.lower()
    keyword = KEYWORDS.get(lowered)
    if keyword is not None:
        return OpToken(keyword, start)
    if lowered.startswith(TYPE_PREFIX):
        value = TYPES.get(lowered[len(TYPE_PREFIX):])
        if value is None:
            choices = ", ".join(TYPES)
            raise TagExpressionError(TagExprErrorCode.unknown_type, start, f"unknown snippet type; expected one of {choices}")
        return TypeToken(value, start)
    return TagToken(word, start)
