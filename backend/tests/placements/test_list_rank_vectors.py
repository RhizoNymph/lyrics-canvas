"""The shared list-rank vectors (spec/list_rank_vectors.json): canonical here, generated from the
Python port by scripts/export_list_rank_vectors.py; the web and Android ports copy the file."""

from __future__ import annotations

import json

import pytest

from app.services.placements.errors import InvalidListRankError
from app.services.placements.list_rank import key_between, n_keys_between, validate_rank
from scripts.export_list_rank_vectors import VECTORS_PATH, render_vectors

REGENERATE = "re-run `uv run python -m scripts.export_list_rank_vectors` from backend/ and commit spec/list_rank_vectors.json"
VECTORS = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))


def test_vector_file_shape():
    assert set(VECTORS) == {"between", "n_between", "invalid"}
    assert len(VECTORS["between"]) > 100 and VECTORS["n_between"] and VECTORS["invalid"]


def test_vector_file_is_up_to_date():
    assert VECTORS_PATH.read_text(encoding="utf-8") == render_vectors(), REGENERATE


@pytest.mark.parametrize("case", VECTORS["between"], ids=lambda c: f"{c['a']}..{c['b']}")
def test_between(case):
    key = key_between(case["a"], case["b"])
    assert key == case["key"]
    assert case["a"] is None or case["a"] < key
    assert case["b"] is None or key < case["b"]


@pytest.mark.parametrize("case", VECTORS["n_between"], ids=lambda c: f"{c['a']}..{c['b']}x{c['n']}")
def test_n_between(case):
    keys = n_keys_between(case["a"], case["b"], case["n"])
    assert keys == case["keys"]
    assert len(keys) == case["n"] and keys == sorted(set(keys))


@pytest.mark.parametrize("key", VECTORS["invalid"], ids=repr)
def test_invalid(key):
    with pytest.raises(InvalidListRankError):
        validate_rank(key)


def test_every_generated_key_is_valid():
    for case in VECTORS["between"]:
        validate_rank(case["key"])
    for case in VECTORS["n_between"]:
        for key in case["keys"]:
            validate_rank(key)
