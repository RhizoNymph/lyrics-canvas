"""The shared conformance vectors (spec/tag_expression_vectors.json at the repo root).

The file is byte-identical across the backend, web and Android branches; it is read in place,
never copied, so every implementation is checked against the same cases.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.models.snippet import SnippetType
from app.services.tag_expr import (
    SnippetFacts,
    TagExpressionError,
    format_expression,
    matches,
    normalize,
    parse,
    rename_tag,
)

VECTORS_PATH = Path(__file__).resolve().parents[3] / "spec" / "tag_expression_vectors.json"
VECTORS = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))


def expand(value: str | list) -> str:
    """A vector string: plain, or segments where ``{"repeat": s, "times": n}`` is ``s * n``."""
    if isinstance(value, str):
        return value
    return "".join(seg if isinstance(seg, str) else seg["repeat"] * seg["times"] for seg in value)


def _short(text: str) -> str:
    return text if len(text) <= 40 else f"{text[:37]}..."


def test_vectors_file_has_every_section():
    assert VECTORS["version"] == 1
    for section in ("parse_ok", "parse_error", "eval", "rename"):
        assert VECTORS[section], section


@pytest.mark.parametrize("vector", VECTORS["parse_ok"], ids=lambda v: _short(expand(v["input"])))
def test_parse_ok(vector):
    text = expand(vector["input"])
    want = expand(vector["normalized"])
    assert normalize(text) == want
    # normalization is idempotent
    assert format_expression(parse(want)) == want


@pytest.mark.parametrize("vector", VECTORS["parse_error"], ids=lambda v: _short(expand(v["input"])))
def test_parse_error(vector):
    text = expand(vector["input"])
    with pytest.raises(TagExpressionError) as info:
        parse(text)
    assert (info.value.code.value, info.value.position) == (vector["code"], vector["position"])
    assert info.value.message


@pytest.mark.parametrize("vector", VECTORS["eval"], ids=lambda v: f"{v['expression']} {v['snippet']}")
def test_eval(vector):
    snippet = vector["snippet"]
    facts = SnippetFacts.of(SnippetType(snippet["type"]), snippet["tags"])
    assert matches(parse(vector["expression"]), facts) is vector["matches"]


@pytest.mark.parametrize("vector", VECTORS["rename"], ids=lambda v: f"{v['expression']} {v['from']}->{v['to']}")
def test_rename(vector):
    assert rename_tag(vector["expression"], vector["from"], vector["to"]) == vector["result"]
