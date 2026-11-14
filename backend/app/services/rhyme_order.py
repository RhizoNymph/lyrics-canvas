"""Rhyme list orders: "rhyme" (today's order) and "meaning" ("Rhyme, then meaning").

The single home of the score bands (clients only label them; the server sends ``band``) and of the
pure re-ordering used by rhyme-suggest, rhyme-pattern suggest and word bank rhymes
(docs/features/rhyme_view.md#rhyme-then-meaning).

``meaning`` order: candidates sorted by (band rank asc, similarity desc, score desc, rhyme position
asc), where "rhyme position" is the candidate's index in today's order, so today's tie-break is
kept. Only the first ``pool`` candidates (by today's order) are re-ordered; the rest follow in
today's order with no similarity. Because today's order is by score first, every candidate past
the pool is in the same band as the pool's last one or a lower one, so bands stay in rhyme order.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Generic, Literal, TypeVar

OrderName = Literal["rhyme", "meaning"]
Band = Literal["perfect", "strong", "near", "loose"]

# lower bounds, best band first; a score below the last bound is "loose"
BAND_FLOORS: tuple[tuple[Band, float], ...] = (("perfect", 0.95), ("strong", 0.85), ("near", 0.75))
BAND_ORDER: tuple[Band, ...] = ("perfect", "strong", "near", "loose")
BAND_RANK: dict[Band, int] = {band: i for i, band in enumerate(BAND_ORDER)}

# candidates (by today's order) re-ordered by meaning per list; the rest keep today's order
MEANING_POOL = 1500
SIMILARITY_DECIMALS = 4

T = TypeVar("T")


def band_of(score: float) -> Band:
    for band, floor in BAND_FLOORS:
        if score >= floor:
            return band
    return "loose"


def wire_similarity(value: float) -> float:
    """Cosine similarity as sent (and compared): rounded to 4 decimals."""
    return round(float(value), SIMILARITY_DECIMALS)


@dataclass(frozen=True, slots=True)
class Ordered(Generic[T]):
    """One item of a re-ordered list and its similarity (None past the meaning pool)."""

    item: T
    similarity: float | None


def meaning_pool(items: Sequence[T], pool: int = MEANING_POOL) -> Sequence[T]:
    """The items (today's order) that get a similarity: the first ``pool`` of them."""
    if pool < 0:
        raise ValueError(f"pool must not be negative, got {pool}")
    return items[:pool]


def order_by_meaning(
    items: Sequence[T],
    score: Callable[[T], float],
    similarities: Sequence[float],
    pool: int = MEANING_POOL,
) -> list[Ordered[T]]:
    """``items`` (in today's order) re-ordered by band, similarity, score, then today's position.

    ``similarities`` holds one cosine per item of ``meaning_pool(items, pool)``, same order.
    """
    pooled = meaning_pool(items, pool)
    if len(similarities) != len(pooled):
        raise ValueError(f"expected {len(pooled)} similarities, got {len(similarities)}")
    keyed = [
        (BAND_RANK[band_of(score(item))], -wire_similarity(sim), -score(item), position, item, wire_similarity(sim))
        for position, (item, sim) in enumerate(zip(pooled, similarities))
    ]
    keyed.sort(key=lambda k: k[:4])
    head = [Ordered(item=k[4], similarity=k[5]) for k in keyed]
    return head + [Ordered(item=item, similarity=None) for item in items[len(pooled) :]]


def rhyme_order(items: Sequence[T]) -> list[Ordered[T]]:
    """Today's order, no similarities."""
    return [Ordered(item=item, similarity=None) for item in items]
