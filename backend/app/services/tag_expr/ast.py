"""Expression tree. ``None`` stands for the empty expression, which matches everything.

And/Or always have two or more children and same-kind children are flattened by the parser;
parentheses leave no node. Nothing else is simplified (``!!a`` stays two Nots).
"""

from __future__ import annotations

from dataclasses import dataclass

from app.models.snippet import SnippetType


@dataclass(frozen=True, slots=True)
class Tag:
    name: str

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("a tag name must not be empty or whitespace-only")


@dataclass(frozen=True, slots=True)
class Type:
    value: SnippetType


@dataclass(frozen=True, slots=True)
class Not:
    child: Expr


@dataclass(frozen=True, slots=True)
class And:
    children: tuple[Expr, ...]

    def __post_init__(self) -> None:
        if len(self.children) < 2:
            raise ValueError("And needs at least two children")


@dataclass(frozen=True, slots=True)
class Or:
    children: tuple[Expr, ...]

    def __post_init__(self) -> None:
        if len(self.children) < 2:
            raise ValueError("Or needs at least two children")


Expr = Tag | Type | Not | And | Or
