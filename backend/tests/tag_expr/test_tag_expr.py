"""Tag expression module behaviour beyond the shared vectors: AST shape, invariants, API."""

from __future__ import annotations

import pytest

from app.models.snippet import SnippetType
from app.services.tag_expr import (
    MAX_DEPTH,
    MAX_LENGTH,
    And,
    Not,
    Or,
    SnippetFacts,
    Tag,
    TagExpressionError,
    TagExprErrorCode,
    Type,
    format_expression,
    matches,
    normalize,
    parse,
    rename_in,
    rename_tag,
)


def test_empty_parses_to_none_and_matches_everything():
    assert parse("") is None
    assert parse(" \t\n ") is None
    assert format_expression(None) == ""
    assert matches(None, SnippetFacts.of(SnippetType.hook, []))


def test_ast_shape_is_flattened_and_keeps_double_negation():
    assert parse("a b & (c & d)") == And((Tag("a"), Tag("b"), Tag("c"), Tag("d")))
    assert parse("a | (b | c) | d") == Or((Tag("a"), Tag("b"), Tag("c"), Tag("d")))
    assert parse("!!a") == Not(Not(Tag("a")))
    assert parse("type:Verse") == Type(SnippetType.verse)
    assert parse("a | b & !type:hook") == Or((Tag("a"), And((Tag("b"), Not(Type(SnippetType.hook))))))


def test_mixed_nesting_is_not_flattened_across_kinds():
    assert parse("(a | b) & (c | d)") == And((Or((Tag("a"), Tag("b"))), Or((Tag("c"), Tag("d")))))


def test_quoted_names_are_verbatim():
    assert parse('"  spaced  "') == Tag("  spaced  ")
    assert parse('"type:verse"') == Tag("type:verse")
    assert parse('"and"') == Tag("and")


@pytest.mark.parametrize("children", [(), (Tag("a"),)])
def test_and_or_need_two_children(children):
    with pytest.raises(ValueError):
        And(children)
    with pytest.raises(ValueError):
        Or(children)


def test_tag_name_must_not_be_blank():
    with pytest.raises(ValueError):
        Tag("   ")


def test_nodes_are_frozen():
    node = Tag("a")
    with pytest.raises(AttributeError):
        node.name = "b"  # type: ignore[misc]


def test_error_carries_code_position_and_message():
    with pytest.raises(TagExpressionError) as info:
        parse("love & (")
    err = info.value
    assert err.code is TagExprErrorCode.unexpected_end
    assert err.position == 8
    assert "end" in err.message
    assert err.as_dict() == {"code": "unexpected_end", "position": 8, "message": err.message}


def test_limits_match_the_contract():
    assert MAX_LENGTH == 500
    assert MAX_DEPTH == 32
    normalize("a" * MAX_LENGTH)
    with pytest.raises(TagExpressionError):
        normalize("a" * (MAX_LENGTH + 1))


def test_lexer_errors_win_over_parse_errors():
    # `&` at 0 is a parse error, the bad type at 2 is a lexer error; the lexer runs first
    with pytest.raises(TagExpressionError) as info:
        parse("& type:solo")
    assert info.value.code is TagExprErrorCode.unknown_type


def test_many_negations_do_not_overflow():
    text = "!" * (MAX_LENGTH - 1) + "a"
    assert normalize(text) == text
    assert matches(parse(text), SnippetFacts.of(SnippetType.verse, ["a"])) is False


def test_facts_lowercase_names_once():
    facts = SnippetFacts.of(SnippetType.verse, ["Late Night", "CAFÉ"])
    assert facts.tag_names == frozenset({"late night", "café"})


def test_rename_in_returns_an_ast_and_leaves_types():
    expr = rename_in(parse("verse & type:verse"), "VERSE", "v")
    assert expr == And((Tag("v"), Type(SnippetType.verse)))
    assert rename_in(None, "a", "b") is None


def test_rename_tag_rejects_invalid_expression():
    with pytest.raises(TagExpressionError):
        rename_tag("a &", "a", "b")


@pytest.mark.parametrize(
    "name",
    ["plain", "with space", "and", "Type:verse", 'q"uote', "back\\slash", "a&b", "🔥", "!x", "(x)"],
)
def test_any_tag_name_round_trips_through_format(name):
    text = format_expression(Tag(name))
    assert parse(text) == Tag(name)
