"""Rhyme families over the (filtered) bank (docs/features/word_bank.md#families)."""

from __future__ import annotations

import random

import pytest

from app.services.phonetics.types import Unit
from app.services.word_bank.errors import TooManyWordsError
from app.services.word_bank.families import MAX_FAMILY_WORDS, rhyme_families
from app.services.word_bank.phonetic import PairScorer, phonetic_words


def bank(*texts: str):
    return phonetic_words([(f"id-{t}", t) for t in texts])


class FakeScorer:
    """Similarity from a table keyed by lowercased text pairs; missing pairs are 0."""

    def __init__(self, table: dict[tuple[str, str], float]) -> None:
        self._table = {frozenset(k): v for k, v in table.items()}

    def similarity(self, a: Unit, b: Unit) -> float:
        return self._table.get(frozenset((a.text.lower(), b.text.lower())), 0.0)


def family_texts(result):
    return [[w.text for w in f.words] for f in result.families]


def all_texts(result):
    return sorted([w.text for f in result.families for w in f.words] + [w.text for w in result.unmatched])


# ---- real phonetics ----


def test_real_words_group_into_rhyme_families():
    words = bank("night", "light", "fight", "money", "honey", "table", "heart", "apart", "start")
    result = rhyme_families(words, PairScorer(2), min_score=0.8)
    groups = [set(f) for f in family_texts(result)]
    assert {"night", "light", "fight"} in groups
    assert {"money", "honey"} in groups
    assert {"heart", "apart", "start"} in groups
    assert [w.text for w in result.unmatched] == ["table"]
    assert all_texts(result) == sorted(w.text for w in words)


def test_every_word_lands_exactly_once_and_families_have_two_or_more():
    words = bank("night", "light", "money", "honey", "sunny", "table", "cable", "orange", "夜", "door")
    for min_score in (0.0, 0.5, 0.8, 0.95, 1.0):
        result = rhyme_families(words, PairScorer(2), min_score=min_score)
        ids = [w.id for f in result.families for w in f.words] + [w.id for w in result.unmatched]
        assert sorted(ids) == sorted(w.id for w in words)
        assert all(len(f.words) >= 2 for f in result.families)


def test_unpronounceable_words_are_unmatched():
    words = bank("夜", "night", "light")
    result = rhyme_families(words, PairScorer(2), min_score=0.8)
    assert [w.text for w in result.unmatched] == ["夜"]


def test_min_score_zero_merges_every_pronounceable_word():
    words = bank("night", "money", "table", "夜")
    result = rhyme_families(words, PairScorer(2), min_score=0.0)
    assert len(result.families) == 1
    assert sorted(w.text for w in result.families[0].words) == ["money", "night", "table"]
    assert [w.text for w in result.unmatched] == ["夜"]


def test_input_order_does_not_change_the_result():
    texts = ["night", "light", "fight", "bite", "money", "honey", "sunny", "funny", "heart", "start", "table", "cable"]
    expected = rhyme_families(bank(*texts), PairScorer(2), min_score=0.8)
    rng = random.Random(7)
    for _ in range(5):
        shuffled = texts[:]
        rng.shuffle(shuffled)
        got = rhyme_families(bank(*shuffled), PairScorer(2), min_score=0.8)
        assert family_texts(got) == family_texts(expected)
        assert [w.text for w in got.unmatched] == [w.text for w in expected.unmatched]


def test_family_fields():
    words = bank("night", "light")
    (family,) = rhyme_families(words, PairScorer(2), min_score=0.8).families
    assert family.label == "light"  # tie on mean similarity: lower(text)
    assert family.rhyme_key == ("AY", "T")
    assert family.cohesion == 1.0


def test_empty_bank():
    result = rhyme_families([], PairScorer(2), min_score=0.8)
    assert result.families == () and result.unmatched == ()


def test_too_many_words():
    words = phonetic_words([(str(i), "night") for i in range(MAX_FAMILY_WORDS + 1)])
    with pytest.raises(TooManyWordsError):
        rhyme_families(words, PairScorer(2), min_score=0.8)
    assert MAX_FAMILY_WORDS == 1000


def test_exactly_the_limit_is_allowed():
    words = phonetic_words([(str(i), t) for i, t in enumerate(["night", "light"] * 3)])
    assert len(rhyme_families(words, PairScorer(2), min_score=0.8).families) == 1


# ---- the algorithm, with a fake similarity table ----


def test_average_linkage_stops_below_min_score():
    table = {
        ("a", "b"): 0.9, ("c", "d"): 0.85,
        ("a", "c"): 0.8, ("a", "d"): 0.8, ("b", "c"): 0.8, ("b", "d"): 0.6,
    }
    result = rhyme_families(bank("a", "b", "c", "d"), FakeScorer(table), min_score=0.8)
    # {a,b} and {c,d} average (0.8+0.8+0.8+0.6)/4 = 0.75 < 0.8
    assert sorted(sorted(f) for f in family_texts(result)) == [["a", "b"], ["c", "d"]]


def test_average_linkage_merges_at_exactly_min_score():
    table = {
        ("a", "b"): 0.9, ("c", "d"): 0.85,
        ("a", "c"): 0.8, ("a", "d"): 0.8, ("b", "c"): 0.8, ("b", "d"): 0.8,
    }
    result = rhyme_families(bank("a", "b", "c", "d"), FakeScorer(table), min_score=0.8)
    assert [sorted(f) for f in family_texts(result)] == [["a", "b", "c", "d"]]


def test_single_linkage_would_chain_but_average_does_not():
    table = {("a", "b"): 0.9, ("b", "c"): 0.85, ("a", "c"): 0.1}
    result = rhyme_families(bank("a", "b", "c"), FakeScorer(table), min_score=0.8)
    assert family_texts(result) == [["a", "b"]]
    assert [w.text for w in result.unmatched] == ["c"]


@pytest.mark.parametrize("order", [("alpha", "beta", "gamma"), ("gamma", "beta", "alpha"), ("beta", "gamma", "alpha")])
def test_ties_merge_the_pair_with_the_lowest_texts_first(order):
    # (alpha, beta) and (beta, gamma) tie; alpha < gamma so alpha+beta merge, then gamma is too far
    table = {("alpha", "beta"): 0.9, ("beta", "gamma"): 0.9, ("alpha", "gamma"): 0.5}
    result = rhyme_families(bank(*order), FakeScorer(table), min_score=0.8)
    assert [sorted(f) for f in family_texts(result)] == [["alpha", "beta"]]
    assert [w.text for w in result.unmatched] == ["gamma"]


def test_tie_break_uses_lower_text_of_each_cluster():
    # Zed+Ann merge first (0.95); then (cluster{Ann,Zed}, bob) and (cat, dog) tie at 0.9.
    # The first cluster's lowest text is "ann" < "cat": it merges with bob, leaving cat+dog.
    table = {
        ("zed", "ann"): 0.95,
        ("ann", "bob"): 0.9, ("zed", "bob"): 0.9,
        ("cat", "dog"): 0.9,
        ("bob", "cat"): 0.9, ("ann", "cat"): 0.0, ("zed", "cat"): 0.0,
    }
    result = rhyme_families(bank("Zed", "Ann", "bob", "cat", "dog"), FakeScorer(table), min_score=0.8)
    assert sorted(sorted(f, key=str.lower) for f in family_texts(result)) == [["Ann", "bob", "Zed"], ["cat", "dog"]]


def test_medoid_label_and_word_order():
    table = {
        ("a", "b"): 0.9, ("a", "c"): 0.95, ("a", "d"): 0.9,
        ("b", "c"): 0.85, ("b", "d"): 0.85, ("c", "d"): 0.85,
    }
    (family,) = rhyme_families(bank("d", "c", "b", "a"), FakeScorer(table), min_score=0.8).families
    # means: a .9167, b .8667, c .8833, d .8667
    assert family.label == "a"
    assert [w.text for w in family.words] == ["a", "c", "b", "d"]  # then similarity to a, then text
    assert family.cohesion == pytest.approx((0.9 + 0.95 + 0.9 + 0.85 + 0.85 + 0.85) / 6)


def test_medoid_ties_go_to_lower_text():
    table = {("b", "c"): 0.9, ("b", "a"): 0.9, ("a", "c"): 0.9}
    (family,) = rhyme_families(bank("c", "b", "a"), FakeScorer(table), min_score=0.8).families
    assert family.label == "a"
    assert [w.text for w in family.words] == ["a", "b", "c"]


def test_families_sort_by_size_then_cohesion_then_label():
    table = {
        ("p", "q"): 0.9, ("p", "r"): 0.9, ("q", "r"): 0.9,  # size 3
        ("x", "y"): 0.85,  # size 2, cohesion .85
        ("m", "n"): 0.95,  # size 2, cohesion .95
        ("e", "f"): 0.95,  # size 2, cohesion .95, label e < m
    }
    words = bank("x", "y", "m", "n", "p", "q", "r", "e", "f", "lonely", "Alone")
    result = rhyme_families(words, FakeScorer(table), min_score=0.8)
    assert [f.label for f in result.families] == ["p", "e", "m", "x"]
    assert [w.text for w in result.unmatched] == ["Alone", "lonely"]


def test_unmatched_sorts_by_lower_text():
    result = rhyme_families(bank("b", "C", "a"), FakeScorer({}), min_score=0.8)
    assert result.families == ()
    assert [w.text for w in result.unmatched] == ["a", "b", "C"]
