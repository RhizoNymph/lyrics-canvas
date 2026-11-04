"""Normalized text of an expression; ``parse(format_expression(e)) == e``."""

from __future__ import annotations

from app.services.tag_expr.ast import And, Expr, Not, Or, Tag, Type
from app.services.tag_expr.lexer import KEYWORDS, TYPE_PREFIX, is_word_char


def format_expression(expr: Expr | None) -> str:
    if expr is None:
        return ""
    # Not chains are unrolled so a long `!!!…a` does not recurse once per `!`
    bangs = 0
    while isinstance(expr, Not):
        bangs += 1
        expr = expr.child
    prefix = "!" * bangs
    match expr:
        case Tag(name=name):
            return prefix + format_tag_name(name)
        case Type(value=value):
            return prefix + TYPE_PREFIX + value.value
        case And(children=children):
            text = " & ".join(_wrap_if(c, Or) for c in children)
            return f"{prefix}({text})" if bangs else text
        case Or(children=children):
            text = " | ".join(format_expression(c) for c in children)
            return f"{prefix}({text})" if bangs else text
    raise TypeError(f"not an expression node: {expr!r}")


def _wrap_if(child: Expr, kind: type[Or]) -> str:
    text = format_expression(child)
    return f"({text})" if isinstance(child, kind) else text


def format_tag_name(name: str) -> str:
    """A name bare when it lexes back as itself, else quoted with ``\\`` and ``"`` escaped."""
    if is_bare_name(name):
        return name
    escaped = name.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def is_bare_name(name: str) -> bool:
    if not name or not all(is_word_char(c) for c in name):
        return False
    lowered = name.lower()
    return lowered not in KEYWORDS and not lowered.startswith(TYPE_PREFIX)
