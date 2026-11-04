"""Tag expression language: lex, parse, format (normalize), evaluate, rename.

The canonical spec is docs/features/tag_expressions.md; spec/tag_expression_vectors.json holds the
conformance vectors shared with the web and Android implementations.
"""

from app.services.tag_expr.ast import And, Expr, Not, Or, Tag, Type
from app.services.tag_expr.errors import TagExpressionError, TagExprErrorCode
from app.services.tag_expr.evaluate import SnippetFacts, matches
from app.services.tag_expr.format import format_expression, format_tag_name
from app.services.tag_expr.lexer import MAX_LENGTH
from app.services.tag_expr.parser import MAX_DEPTH, parse
from app.services.tag_expr.rename import rename_in, rename_tag


def normalize(text: str) -> str:
    """The normalized form of ``text``. Raises TagExpressionError."""
    return format_expression(parse(text))


__all__ = [
    "And",
    "Expr",
    "MAX_DEPTH",
    "MAX_LENGTH",
    "Not",
    "Or",
    "SnippetFacts",
    "Tag",
    "TagExprErrorCode",
    "TagExpressionError",
    "Type",
    "format_expression",
    "format_tag_name",
    "matches",
    "normalize",
    "parse",
    "rename_in",
    "rename_tag",
]
