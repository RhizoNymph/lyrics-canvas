"""Rhythm order: band, then rhythm fit, then meaning similarity or rhyme order.

Applied on top of the output of ``apply_order`` (``rhyme`` or ``meaning``, whichever was applied),
which is already sorted by band and, within a band, by similarity (meaning) or score and today's
tie-breaks (rhyme). A stable sort by ``(band rank, -fit, similarity missing, -similarity)`` keeps
that order inside every (band, fit) group, so the full key is
band -> fit -> similarity (meaning) -> score -> today's position.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from typing import TypeVar

from app.services.rhyme_order import BAND_RANK, Ordered, band_of

T = TypeVar("T")


def order_by_rhythm(items: Sequence[Ordered[T]], score: Callable[[T], float], fit: Callable[[T], float]) -> list[Ordered[T]]:
    """``items`` re-sorted by band, rhythm fit desc, similarity desc (pooled first); stable otherwise."""

    def key(o: Ordered[T]) -> tuple[int, float, bool, float]:
        similarity = o.similarity
        return (BAND_RANK[band_of(score(o.item))], -fit(o.item), similarity is None, -(similarity or 0.0))

    return sorted(items, key=key)
