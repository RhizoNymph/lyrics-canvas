"""order_by_rhythm: band, then fit, then similarity (pooled first), stable otherwise."""

from __future__ import annotations

from dataclasses import dataclass

from app.services.rhyme_order import Ordered, rhyme_order
from app.services.rhythm_fit import order_by_rhythm


@dataclass(frozen=True)
class Item:
    name: str
    score: float
    fit: float


def names(items):
    return [o.item.name for o in items]


def run(items):
    return order_by_rhythm(items, score=lambda i: i.score, fit=lambda i: i.fit)


def test_bands_come_first_then_fit_then_todays_order():
    today = [
        Item("a", 1.0, 0.5), Item("b", 0.97, 1.0), Item("c", 0.96, 0.5),  # perfect
        Item("d", 0.9, 0.25), Item("e", 0.86, 0.75),                       # strong
        Item("f", 0.6, 1.0),                                               # loose
    ]
    out = run(rhyme_order(today))
    assert names(out) == ["b", "a", "c", "e", "d", "f"]
    assert all(o.similarity is None for o in out)


def test_fit_never_lifts_a_candidate_over_a_better_band():
    today = [Item("perfect-bad", 1.0, 0.0), Item("near-great", 0.8, 1.0)]
    assert names(run(rhyme_order(today))) == ["perfect-bad", "near-great"]


def test_equal_fits_keep_the_input_order():
    today = [Item(str(i), 0.9, 0.5) for i in range(10)]
    assert names(run(rhyme_order(today))) == [str(i) for i in range(10)]


def test_meaning_similarity_breaks_fit_ties_and_unpooled_follow():
    # the input as apply_order(meaning) returns it: band, similarity desc; the last one past the pool
    meaning = [
        Ordered(Item("x", 1.0, 0.5), 0.9),
        Ordered(Item("y", 0.96, 1.0), 0.2),
        Ordered(Item("z", 0.99, 0.5), 0.1),
        Ordered(Item("w", 0.95, 0.5), None),
    ]
    out = run(meaning)
    assert names(out) == ["y", "x", "z", "w"]
    assert [o.similarity for o in out] == [0.2, 0.9, 0.1, None]


def test_empty_input():
    assert run([]) == []
