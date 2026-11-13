"""Whole-line matches: which slots one note line already hits, in slot order and word order."""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class HitOption:
    """A span ``[word_start, word_end)`` of the line that rhymes with every member of ``slot``
    (an index into the slot order); ``score`` = its weakest member score."""

    slot: int
    word_start: int
    word_end: int
    score: float
    unit_id: str


def best_hits(options: Sequence[HitOption], word_count: int, slot_count: int) -> tuple[HitOption, ...]:
    """The most hits (then the highest summed score) with at most one hit per slot, slots in
    order along the line and hits not overlapping. A DP over (slot, word position); ties keep
    the first path found (options are visited sorted by slot, start, end, unit id)."""
    if not options:
        return ()
    starts: dict[tuple[int, int], list[HitOption]] = {}
    for option in sorted(options, key=lambda o: (o.slot, o.word_start, o.word_end, o.unit_id)):
        starts.setdefault((option.slot, option.word_start), []).append(option)
    Value = tuple[int, float]
    best: list[list[Value | None]] = [[None] * (word_count + 1) for _ in range(slot_count + 1)]
    back: list[list[tuple[int, int, HitOption | None] | None]] = [[None] * (word_count + 1) for _ in range(slot_count + 1)]
    best[0][0] = (0, 0.0)

    def relax(s: int, p: int, value: Value, frm: tuple[int, int], hit: HitOption | None) -> None:
        current = best[s][p]
        if current is None or value > current:
            best[s][p] = value
            back[s][p] = (frm[0], frm[1], hit)

    for s in range(slot_count + 1):
        for p in range(word_count + 1):
            current = best[s][p]
            if current is None:
                continue
            if s < slot_count:
                for option in starts.get((s, p), ()):
                    relax(s + 1, option.word_end, (current[0] + 1, current[1] + option.score), (s, p), option)
                relax(s + 1, p, current, (s, p), None)
            if p < word_count:
                relax(s, p + 1, current, (s, p), None)
    hits: list[HitOption] = []
    s, p = slot_count, word_count
    while (s, p) != (0, 0):
        entry = back[s][p]
        if entry is None:
            break
        s, p, hit = entry
        if hit is not None:
            hits.append(hit)
    hits.reverse()
    return tuple(hits)
