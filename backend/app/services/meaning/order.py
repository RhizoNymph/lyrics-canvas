"""Apply the requested rhyme list order with the meaning service (async glue over rhyme_order)."""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import Generic, TypeVar

from app.services.meaning.service import MeaningService, TargetPart
from app.services.rhyme_order import MEANING_POOL, OrderName, Ordered, meaning_pool, order_by_meaning, rhyme_order

T = TypeVar("T")


@dataclass(frozen=True, slots=True)
class OrderOutcome(Generic[T]):
    items: list[Ordered[T]]
    applied: OrderName


async def apply_order(
    order: OrderName,
    items: Sequence[T],
    *,
    score: Callable[[T], float],
    text: Callable[[T], str],
    target: Sequence[TargetPart],
    meaning: MeaningService | None,
    pool: int = MEANING_POOL,
) -> OrderOutcome[T]:
    """``items`` (today's order) in the requested order. Meaning falls back to today's order, with
    ``applied == "rhyme"``, when no service is given, the target is empty or the model is unavailable."""
    if order == "rhyme" or meaning is None or not target:
        return OrderOutcome(rhyme_order(items), "rhyme")
    pooled = meaning_pool(items, pool)
    sims = await meaning.similarities(target, [text(i) for i in pooled])
    if sims is None:
        return OrderOutcome(rhyme_order(items), "rhyme")
    return OrderOutcome(order_by_meaning(items, score, sims, pool), "meaning")


@dataclass(frozen=True, slots=True)
class ListsOutcome(Generic[T]):
    lists: list[list[Ordered[T]]]
    applied: OrderName


async def apply_orders(
    order: OrderName,
    lists: Sequence[Sequence[T]],
    *,
    score: Callable[[T], float],
    text: Callable[[T], str],
    target: Sequence[TargetPart],
    meaning: MeaningService | None,
    pool: int = MEANING_POOL,
) -> ListsOutcome[T]:
    """``apply_order`` for several lists against one target, with one embedding call (pattern
    slot columns). The pool applies per list. All lists fall back together."""
    if order == "rhyme" or meaning is None or not target:
        return ListsOutcome([rhyme_order(items) for items in lists], "rhyme")
    pooled = [meaning_pool(items, pool) for items in lists]
    sims = await meaning.similarities(target, [text(i) for items in pooled for i in items])
    if sims is None:
        return ListsOutcome([rhyme_order(items) for items in lists], "rhyme")
    ordered: list[list[Ordered[T]]] = []
    start = 0
    for items, head in zip(lists, pooled):
        ordered.append(order_by_meaning(items, score, sims[start : start + len(head)], pool))
        start += len(head)
    return ListsOutcome(ordered, "meaning")
