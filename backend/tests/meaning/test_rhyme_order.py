"""Bands and the pure "Rhyme, then meaning" ordering (app/services/rhyme_order.py)."""

from dataclasses import dataclass

import pytest

from app.services.rhyme_order import (
    BAND_FLOORS,
    BAND_ORDER,
    BAND_RANK,
    MEANING_POOL,
    band_of,
    meaning_pool,
    order_by_meaning,
    rhyme_order,
    wire_similarity,
)


@dataclass(frozen=True)
class Item:
    name: str
    score: float


def _score(item: Item) -> float:
    return item.score


@pytest.mark.parametrize(
    "score,band",
    [
        (1.0, "perfect"), (0.95, "perfect"), (0.9499, "strong"), (0.85, "strong"), (0.8499, "near"),
        (0.75, "near"), (0.7499, "loose"), (0.5, "loose"), (0.0, "loose"),
    ],
)
def test_band_boundaries(score, band):
    assert band_of(score) == band


def test_band_constants_agree():
    assert BAND_ORDER == ("perfect", "strong", "near", "loose")
    assert [b for b, _ in BAND_FLOORS] == list(BAND_ORDER[:-1])
    floors = [f for _, f in BAND_FLOORS]
    assert floors == sorted(floors, reverse=True) == [0.95, 0.85, 0.75]
    assert BAND_RANK == {"perfect": 0, "strong": 1, "near": 2, "loose": 3}
    assert MEANING_POOL == 1500


def test_wire_similarity_rounds_to_four_decimals():
    assert wire_similarity(0.123456) == 0.1235
    assert wire_similarity(-0.99999) == -1.0


def test_rhyme_order_keeps_order_and_has_no_similarity():
    items = [Item("a", 1.0), Item("b", 0.5)]
    assert [(o.item, o.similarity) for o in rhyme_order(items)] == [(items[0], None), (items[1], None)]


def test_bands_first_then_similarity_then_score_then_rhyme_position():
    # today's order: by score desc
    items = [
        Item("p1", 1.0), Item("p2", 0.96), Item("s1", 0.9), Item("s2", 0.86), Item("s3", 0.86),
        Item("n1", 0.8), Item("l1", 0.7), Item("l2", 0.6),
    ]
    sims = [0.1, 0.9, 0.2, 0.2, 0.2, 0.5, 0.0, 0.8]
    ordered = order_by_meaning(items, _score, sims)
    assert [o.item.name for o in ordered] == ["p2", "p1", "s1", "s2", "s3", "n1", "l2", "l1"]
    # s1 and s2/s3 tie on similarity: higher score first; s2 and s3 tie on both: rhyme position
    assert [o.similarity for o in ordered] == [0.9, 0.1, 0.2, 0.2, 0.2, 0.5, 0.8, 0.0]


def test_bands_never_interleave():
    items = [Item(str(i), s) for i, s in enumerate([0.99, 0.97, 0.9, 0.88, 0.8, 0.76, 0.7, 0.5])]
    sims = [0.0, 0.1, 0.9, 1.0, 0.95, 0.2, 0.99, 0.3]
    ordered = order_by_meaning(items, _score, sims)
    ranks = [BAND_RANK[band_of(o.item.score)] for o in ordered]
    assert ranks == sorted(ranks)


def test_similarity_ties_are_compared_after_rounding():
    items = [Item("a", 0.9), Item("b", 0.91)]
    ordered = order_by_meaning(items, _score, [0.500001, 0.500002])
    assert [o.item.name for o in ordered] == ["b", "a"]  # equal at 4 decimals, so the score decides
    assert all(o.similarity == 0.5 for o in ordered)


def test_pool_reorders_the_head_and_appends_the_rest_in_rhyme_order():
    items = [Item("a", 1.0), Item("b", 0.99), Item("c", 0.98), Item("d", 0.97), Item("e", 0.5)]
    pooled = meaning_pool(items, pool=3)
    assert list(pooled) == items[:3]
    ordered = order_by_meaning(items, _score, [0.1, 0.2, 0.3], pool=3)
    assert [o.item.name for o in ordered] == ["c", "b", "a", "d", "e"]
    assert [o.similarity for o in ordered] == [0.3, 0.2, 0.1, None, None]


def test_pool_larger_than_the_list_covers_everything():
    items = [Item("a", 1.0), Item("b", 0.5)]
    assert len(meaning_pool(items, pool=10)) == 2
    assert [o.item.name for o in order_by_meaning(items, _score, [0.0, 1.0], pool=10)] == ["a", "b"]


def test_similarity_count_must_match_the_pool():
    with pytest.raises(ValueError):
        order_by_meaning([Item("a", 1.0)], _score, [])
    with pytest.raises(ValueError):
        meaning_pool([], pool=-1)


def test_empty_list():
    assert order_by_meaning([], _score, []) == []
