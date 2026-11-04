"""Rename a tag inside an expression (used when a tag is renamed, to rewrite presets)."""

from __future__ import annotations

from app.services.tag_expr.ast import And, Expr, Not, Or, Tag, Type
from app.services.tag_expr.format import format_expression
from app.services.tag_expr.parser import parse


def rename_in(expr: Expr | None, old: str, new: str) -> Expr | None:
    """Every Tag equal to ``old`` under lowercasing becomes ``Tag(new)``; Type terms are untouched."""
    if expr is None:
        return None
    return _rename(expr, old.lower(), new)


def _rename(expr: Expr, old_lower: str, new: str) -> Expr:
    bangs = 0
    while isinstance(expr, Not):
        bangs += 1
        expr = expr.child
    match expr:
        case Tag(name=name):
            out: Expr = Tag(new) if name.lower() == old_lower else expr
        case Type():
            out = expr
        case And(children=children):
            out = And(tuple(_rename(c, old_lower, new) for c in children))
        case Or(children=children):
            out = Or(tuple(_rename(c, old_lower, new) for c in children))
        case _:
            raise TypeError(f"not an expression node: {expr!r}")
    for _ in range(bangs):
        out = Not(out)
    return out


def rename_tag(text: str, old: str, new: str) -> str:
    """Parse ``text``, rename, and return the normalized result. Raises TagExpressionError."""
    return format_expression(rename_in(parse(text), old, new))
