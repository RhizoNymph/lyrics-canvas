"""Pure parts of search: query normalisation and validation, matching, ILIKE escaping, excerpts."""

from __future__ import annotations

import unicodedata

import pytest

from app.services.search.errors import InvalidSearchQueryError, SearchErrorCode
from app.services.search.excerpt import EXCERPT_MAX, excerpt
from app.services.search.matching import (
    matches_terms,
    normalize_terms,
    normalize_text,
    title_matches,
)
from app.services.search.query import MAX_QUERY_LENGTH, SearchQuery, parse_search_query
from app.services.search.sql import like_pattern


# ---- normalisation and matching ----


def test_normalize_terms_nfc_trim_split_lower():
    nfd = unicodedata.normalize("NFD", "Café")
    assert normalize_terms(f"  {nfd}   AU\tLait ") == ("café", "au", "lait")
    assert normalize_terms("   ") == ()


def test_normalize_text_nfc_lower():
    assert normalize_text(unicodedata.normalize("NFD", "ÉTÉ")) == "été"


def test_matches_terms_all_terms_on_display_text():
    assert matches_terms(("go", "now"), "go_now")
    assert not matches_terms(("_",), "go_now")
    assert matches_terms((), "anything")


def test_terms_do_not_match_across_a_rest():
    # "o_n" is not in the display text "go now"
    assert not matches_terms(("o_n",), "go_now")


def test_nfd_text_matches_nfc_query():
    assert matches_terms(normalize_terms("café"), unicodedata.normalize("NFD", "Café au lait"))


def test_title_matches_is_plain_substring():
    assert title_matches(("song",), "My Song")
    assert title_matches(("my_s",), "my_song")
    assert not title_matches(("song", "x"), "My Song")


# ---- query validation ----


def test_parse_search_query_ok():
    query = parse_search_query("  Black CAT ")
    assert query == SearchQuery(text="Black CAT", terms=("black", "cat"))


@pytest.mark.parametrize("raw", [None, "", "   ", "\t\n", "x" * (MAX_QUERY_LENGTH + 1)])
def test_parse_search_query_rejects(raw):
    with pytest.raises(InvalidSearchQueryError) as info:
        parse_search_query(raw)
    assert info.value.code is SearchErrorCode.invalid_search_query
    assert info.value.status == 422
    assert info.value.message


def test_parse_search_query_limit_counts_code_points_after_trim():
    assert MAX_QUERY_LENGTH == 200
    parse_search_query("  " + "é" * MAX_QUERY_LENGTH + "  ")
    # NFD é is two code points; NFC makes it one, so 200 of them fit
    parse_search_query(unicodedata.normalize("NFD", "é" * MAX_QUERY_LENGTH))


def test_search_query_needs_terms():
    with pytest.raises(ValueError):
        SearchQuery(text="", terms=())


# ---- ILIKE escaping ----


@pytest.mark.parametrize(
    ("term", "pattern"),
    [("cat", "%cat%"), ("100%", "%100\\%%"), ("a_b", "%a\\_b%"), ("back\\slash", "%back\\\\slash%")],
)
def test_like_pattern_escapes_wildcards(term, pattern):
    assert like_pattern(term) == pattern


# ---- excerpt ----


def test_excerpt_is_first_display_line_with_first_term():
    text = "intro line\n\nthe black _ cat\nanother cat"
    assert excerpt(text, ("cat", "intro")) == "the black cat"


def test_excerpt_falls_back_to_first_non_empty_display_line():
    assert excerpt("_\n\nfirst\nsecond", ("zzz",)) == "first"


def test_excerpt_of_empty_display_text():
    assert excerpt("_ _", ("x",)) == ""


def test_excerpt_matches_case_and_normalisation_insensitively():
    assert excerpt("one\nTWO Café", ("café",)) == "TWO Café"


def test_long_line_excerpt_keeps_the_match_in_view():
    line = " ".join(["filler"] * 60) + " needle " + " ".join(["tail"] * 40)
    out = excerpt(line, ("needle",))
    assert len(out) <= EXCERPT_MAX
    assert "needle" in out
    assert out.startswith("…") and out.endswith("…")


def test_long_line_excerpt_from_start_when_match_is_early():
    line = "needle " + "x" * 300
    out = excerpt(line, ("needle",))
    assert out.startswith("needle")
    assert out.endswith("…")
    assert len(out) == EXCERPT_MAX


def test_short_line_is_returned_whole():
    assert excerpt("x" * EXCERPT_MAX, ("x",)) == "x" * EXCERPT_MAX
