"""Validation of tag names, colors, preset names and preset expressions."""

from __future__ import annotations

import pytest

from app.services.tag_errors import (
    EmptyPresetError,
    InvalidColorError,
    InvalidPresetNameError,
    InvalidTagExpressionError,
    InvalidTagNameError,
    TagErrorCode,
)
from app.services.tag_expr import TagExprErrorCode
from app.services.tag_values import preset_expression, preset_name, tag_color, tag_name


@pytest.mark.parametrize(
    ("raw", "want"),
    [("love", "love"), ("  late night  ", "late night"), ("🔥", "🔥"), ("x" * 100, "x" * 100), ("type:verse", "type:verse"), ("é" * 100, "é" * 100)],
)
def test_tag_name_is_trimmed(raw, want):
    assert tag_name(raw) == want


@pytest.mark.parametrize("raw", ["", "   ", "x" * 101, "a\nb", "tab\there", "nul\x00", "del\x7f"])
def test_bad_tag_names(raw):
    with pytest.raises(InvalidTagNameError) as info:
        tag_name(raw)
    assert info.value.code is TagErrorCode.invalid_tag_name
    assert info.value.status == 422


def test_preset_name_follows_the_same_rule_with_its_own_code():
    assert preset_name("  Mine ") == "Mine"
    with pytest.raises(InvalidPresetNameError) as info:
        preset_name("")
    assert info.value.code is TagErrorCode.invalid_preset_name


@pytest.mark.parametrize(("raw", "want"), [("#6366f1", "#6366f1"), ("#ABCDEF", "#abcdef"), ("#000000", "#000000")])
def test_colors_are_lowercased(raw, want):
    assert tag_color(raw) == want


@pytest.mark.parametrize("raw", ["", "6366f1", "#fff", "#6366f1 ", "#gggggg", "#6366f10", "red"])
def test_bad_colors(raw):
    with pytest.raises(InvalidColorError) as info:
        tag_color(raw)
    assert info.value.code is TagErrorCode.invalid_color


def test_preset_expression_is_normalized():
    assert preset_expression("love night or TYPE:Hook") == "love & night | type:hook"


@pytest.mark.parametrize("raw", ["", "   "])
def test_empty_preset_is_rejected(raw):
    with pytest.raises(EmptyPresetError) as info:
        preset_expression(raw)
    assert info.value.code is TagErrorCode.empty_preset


def test_invalid_preset_expression_carries_the_parse_error():
    with pytest.raises(InvalidTagExpressionError) as info:
        preset_expression("love &")
    err = info.value
    assert err.code is TagErrorCode.invalid_tag_expression
    assert err.expression_error.code is TagExprErrorCode.unexpected_end
    assert err.expression_error.position == 6


def test_preset_whose_normal_form_outgrows_the_limit_is_rejected():
    # 250 juxtaposed words is 499 characters; normalized with " & " it is far longer
    text = " ".join(["a"] * 250)
    with pytest.raises(InvalidTagExpressionError) as info:
        preset_expression(text)
    assert info.value.expression_error.code is TagExprErrorCode.too_long
