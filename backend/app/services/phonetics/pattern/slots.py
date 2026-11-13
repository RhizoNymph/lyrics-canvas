"""Slot summaries (k, cohesion, shape), slot order and the slot cap."""

from __future__ import annotations

import heapq
from collections import Counter
from collections.abc import Mapping, Sequence

from app.services.phonetics.pattern.pair import PairScorer
from app.services.phonetics.pattern.types import PatternSlot, PatternSpan
from app.services.phonetics.score import MAX_K

MAX_SLOTS = 8


def slot_shape(members: Sequence[PatternSpan]) -> str:
    """The stress string of the members' rhyme tails: the most common tail length (ties: the
    longest), taken from the last member of that length (nearest to where the user writes next)."""
    counts = Counter(m.tail for m in members)
    length = max(counts, key=lambda n: (counts[n], n))
    last = next(m for m in reversed(members) if m.tail == length)
    return last.tail_stress


def summarize(
    members: Sequence[PatternSpan], scorer: PairScorer, line_count: int, k: int | None = None
) -> PatternSlot:
    """A slot from its members (sorted into line order). ``k`` overrides the derived depth: the
    weakest pairwise ``match_depth`` (one member: its stressed tail), clamped to 1..4.
    ``cohesion`` is the weakest pairwise score at ``min(k_a, k_b)`` (1.0 for one member)."""
    ordered = tuple(sorted(members, key=lambda m: m.pos))
    scores = [scorer.at(a, b, min(a.k, b.k)) for i, a in enumerate(ordered) for b in ordered[i + 1 :]]
    cohesion = min((s.score for s in scores), default=1.0)
    if k is None:
        k = min((s.match_depth for s in scores), default=ordered[0].k)
    return PatternSlot(
        members=ordered,
        k=max(1, min(MAX_K, k)),
        cohesion=cohesion,
        stress=slot_shape(ordered),
        line_count=line_count,
    )


def _relative_position(slot: PatternSlot, word_counts: Mapping[int, int]) -> float:
    positions = [m.pos.word_start / max(1, word_counts.get(m.pos.line_index, 1)) for m in slot.members]
    return sum(positions) / len(positions)


def order_slots(slots: Sequence[PatternSlot], word_counts: Mapping[int, int]) -> list[PatternSlot]:
    """Word order on every line, then mean relative position (member start / line word count).

    A topological order of "precedes on a shared line", picking the slot with the smallest mean
    relative position (then the input order) among those free to come next. The input must be
    order-consistent (no two slots in opposite order on two lines)."""
    n = len(slots)
    after: list[set[int]] = [set() for _ in range(n)]
    indegree = [0] * n
    by_line: dict[int, list[tuple[int, int]]] = {}
    for i, slot in enumerate(slots):
        for m in slot.members:
            by_line.setdefault(m.pos.line_index, []).append((m.pos.word_start, i))
    for entries in by_line.values():
        entries.sort()
        for (_, a), (_, b) in zip(entries, entries[1:]):
            if b not in after[a]:
                after[a].add(b)
                indegree[b] += 1
    keys = [(_relative_position(s, word_counts), i) for i, s in enumerate(slots)]
    ready = [keys[i] for i in range(n) if indegree[i] == 0]
    heapq.heapify(ready)
    ordered: list[PatternSlot] = []
    while ready:
        _, i = heapq.heappop(ready)
        ordered.append(slots[i])
        for j in sorted(after[i]):
            indegree[j] -= 1
            if indegree[j] == 0:
                heapq.heappush(ready, keys[j])
    if len(ordered) != n:
        raise ValueError("slots are not order-consistent")
    return ordered


def cap_slots(slots: Sequence[PatternSlot], max_slots: int = MAX_SLOTS) -> list[PatternSlot]:
    """The ``max_slots`` strongest (coverage, then cohesion, then earlier), in their input order."""
    if len(slots) <= max_slots:
        return list(slots)
    ranked = sorted(range(len(slots)), key=lambda i: (-slots[i].coverage, -slots[i].cohesion, i))
    keep = sorted(ranked[:max_slots])
    return [slots[i] for i in keep]
