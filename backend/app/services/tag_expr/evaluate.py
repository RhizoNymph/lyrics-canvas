"""Match an expression against a snippet's type and tag names."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass

from app.models.snippet import SnippetType
from app.services.tag_expr.ast import And, Expr, Not, Or, Tag, Type


@dataclass(frozen=True, slots=True)
class SnippetFacts:
    """What an expression can see of a snippet. ``tag_names`` are lowercased (``str.lower``)."""

    snippet_type: SnippetType
    tag_names: frozenset[str]

    @classmethod
    def of(cls, snippet_type: SnippetType, tag_names: Iterable[str]) -> SnippetFacts:
        return cls(snippet_type, frozenset(name.lower() for name in tag_names))


def matches(expr: Expr | None, facts: SnippetFacts) -> bool:
    """True when the snippet matches; the empty expression (None) matches everything and an
    unknown tag name simply matches nothing."""
    if expr is None:
        return True
    negate = False
    while isinstance(expr, Not):
        negate = not negate
        expr = expr.child
    match expr:
        case Tag(name=name):
            result = name.lower() in facts.tag_names
        case Type(value=value):
            result = facts.snippet_type == value
        case And(children=children):
            result = all(matches(c, facts) for c in children)
        case Or(children=children):
            result = any(matches(c, facts) for c in children)
        case _:
            raise TypeError(f"not an expression node: {expr!r}")
    return result != negate
