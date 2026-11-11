"""The shared rest-mark vectors (tests/fixtures/rest_mark_vectors.json).

This copy is canonical; the web and Android clients copy it byte for byte. Every vector of every
section is checked here.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.services.rest_marks import Rest, Word, display_text, rebuild_line_text, tokenize_line, word_index_per_token
from app.services.search.matching import matches_query

VECTORS_PATH = Path(__file__).resolve().parents[1] / "fixtures" / "rest_mark_vectors.json"
VECTORS = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))


def test_vector_file_version():
    assert VECTORS["version"] == 1
    assert set(VECTORS) == {"version", "tokenize", "word_indexes", "rebuild", "display", "match"}


def _token_json(token: Word | Rest) -> dict:
    match token:
        case Word(text=text):
            return {"word": text}
        case Rest():
            return {"rest": True}


@pytest.mark.parametrize("case", VECTORS["tokenize"], ids=lambda c: repr(c["line"]))
def test_tokenize(case):
    assert [_token_json(t) for t in tokenize_line(case["line"])] == case["tokens"]


@pytest.mark.parametrize("case", VECTORS["word_indexes"], ids=lambda c: repr(c["line"]))
def test_word_indexes(case):
    assert list(word_index_per_token(case["line"])) == case["word_index_per_token"]


@pytest.mark.parametrize("case", VECTORS["rebuild"], ids=lambda c: repr(c["text"]))
def test_rebuild(case):
    assert rebuild_line_text(case["cells"]) == case["text"]


@pytest.mark.parametrize("case", VECTORS["display"], ids=lambda c: repr(c["text"]))
def test_display(case):
    assert display_text(case["text"]) == case["display"]


@pytest.mark.parametrize("case", VECTORS["match"], ids=lambda c: f"{c['query']!r}~{c['text']!r}")
def test_match(case):
    assert matches_query(case["query"], case["text"]) is case["matches"]
