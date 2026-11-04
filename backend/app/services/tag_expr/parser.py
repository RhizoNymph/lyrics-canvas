"""Recursive-descent parser. Precedence ``!`` > ``&`` (or juxtaposition) > ``|``.

    or      := and ( "|" and )*
    and     := unary ( "&"? unary )*
    unary   := "!" unary | primary
    primary := "(" or ")" | TAG | TYPE
"""

from __future__ import annotations

from app.services.tag_expr.ast import And, Expr, Not, Or, Tag, Type
from app.services.tag_expr.errors import TagExpressionError, TagExprErrorCode
from app.services.tag_expr.lexer import Op, OpToken, TagToken, Token, TypeToken, tokenize

MAX_DEPTH = 32  # paren nesting; ``!`` does not count

# tokens that can start a unary, so juxtaposition continues an And
_UNARY_START_OPS = frozenset({Op.not_, Op.lparen})


def parse(text: str) -> Expr | None:
    """The expression tree, or None for an empty/whitespace-only input (matches everything)."""
    tokens = tokenize(text)
    if not tokens:
        return None
    return _Parser(tokens, len(text)).parse()


class _Parser:
    def __init__(self, tokens: list[Token], length: int) -> None:
        self._tokens = tokens
        self._length = length
        self._i = 0

    def parse(self) -> Expr:
        expr = self._or(0)
        leftover = self._peek()
        if leftover is not None:
            raise _unexpected(leftover)
        return expr

    def _peek(self) -> Token | None:
        return self._tokens[self._i] if self._i < len(self._tokens) else None

    def _take(self) -> Token | None:
        token = self._peek()
        self._i += 1
        return token

    def _peek_op(self, *ops: Op) -> bool:
        token = self._peek()
        return isinstance(token, OpToken) and token.op in ops

    def _or(self, depth: int) -> Expr:
        items = [self._and(depth)]
        while self._peek_op(Op.or_):
            self._take()
            items.append(self._and(depth))
        return _flatten(Or, items)

    def _and(self, depth: int) -> Expr:
        items = [self._unary(depth)]
        while True:
            token = self._peek()
            if isinstance(token, OpToken) and token.op is Op.and_:
                self._take()
                items.append(self._unary(depth))
            elif isinstance(token, (TagToken, TypeToken)) or (isinstance(token, OpToken) and token.op in _UNARY_START_OPS):
                items.append(self._unary(depth))
            else:
                return _flatten(And, items)

    def _unary(self, depth: int) -> Expr:
        # iterative so long `!!!…` chains cannot exhaust the stack
        negations = 0
        while self._peek_op(Op.not_):
            self._take()
            negations += 1
        expr = self._primary(depth)
        for _ in range(negations):
            expr = Not(expr)
        return expr

    def _primary(self, depth: int) -> Expr:
        token = self._take()
        match token:
            case None:
                raise TagExpressionError(TagExprErrorCode.unexpected_end, self._length, "the expression ends too early")
            case TagToken(name=name):
                return Tag(name)
            case TypeToken(value=value):
                return Type(value)
            case OpToken(op=Op.lparen, position=position):
                return self._group(depth, position)
            case _:
                raise _unexpected(token)

    def _group(self, depth: int, position: int) -> Expr:
        if depth + 1 > MAX_DEPTH:
            raise TagExpressionError(TagExprErrorCode.too_deep, position, f"parentheses nest deeper than {MAX_DEPTH}")
        if self._peek_op(Op.rparen):
            raise TagExpressionError(TagExprErrorCode.empty_group, position, "empty parentheses")
        inner = self._or(depth + 1)
        closing = self._take()
        if closing is None:
            raise TagExpressionError(TagExprErrorCode.unclosed_paren, position, "this parenthesis is never closed")
        if not (isinstance(closing, OpToken) and closing.op is Op.rparen):
            raise _unexpected(closing)
        return inner


def _unexpected(token: Token) -> TagExpressionError:
    match token:
        case OpToken(op=op):
            what = f"'{op.value}'"
        case TagToken():
            what = "a tag name"
        case TypeToken():
            what = "a type term"
    return TagExpressionError(TagExprErrorCode.unexpected_token, token.position, f"unexpected {what}")


def _flatten(kind: type[And] | type[Or], items: list[Expr]) -> Expr:
    if len(items) == 1:
        return items[0]
    children: list[Expr] = []
    for item in items:
        if isinstance(item, kind):
            children.extend(item.children)
        else:
            children.append(item)
    return kind(tuple(children))
