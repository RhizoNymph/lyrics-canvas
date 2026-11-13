"""Slot detection: progressive, order-preserving alignment of the pattern lines' spans.

1. Spans of every line (``spans.line_spans``) and every passing cross-line pair score
   (``PairScorer.pair``: at ``min(k_a, k_b)``, at least ``min_score``), found through
   last-nucleus buckets. Two spans ending in the same word (ignoring case and punctuation) never
   pair: a repeated word scores 1.0 but is not a rhyme to write to.
2. Pairwise alignment of every two lines: the best chain of span matches that is in order and
   non-overlapping on both lines (Needleman-Wunsch over word positions, gap cost 0). A chain's value is
   ``(sum of scores, sum of match depths k, -words)``: higher score first, then rhymes that cover
   more syllables, then fewer words (so "stay strong" / "along" loses to "strong" / "along").
3. The best pair seeds the profile (one slot per match). Each remaining line, taken in order of
   its best pairwise value against a line already in the profile (ties: lower line index), is
   folded in: a DP over (slot, word position), where a span may join a slot only if it scores
   at least ``min_score`` against every member (value: the mean score), in slot order and word
   order. Then its unused spans may open new slots with an unused span of a profile line
   (greedily, best score first), at the first position in the profile order that keeps word
   order on both lines.
4. Slots (all with coverage >= 2) are summarized, capped at ``max_slots`` (coverage, cohesion)
   and ordered by word order, then mean relative position (``slots.order_slots``).

Deterministic: every choice has a total order (value, then input position).
"""

from __future__ import annotations

import re
from collections.abc import Sequence

from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.distance import vowel_neighbours
from app.services.phonetics.pattern.pair import PairScorer, TailSignature
from app.services.phonetics.pattern.slots import MAX_SLOTS, cap_slots, order_slots, summarize
from app.services.phonetics.pattern.spans import MAX_SPAN_WORDS, line_spans
from app.services.phonetics.pattern.types import LineWords, PatternSlot, PatternSpan, SpanPos
from app.services.phonetics.score import ScoreWeights

DETECT_MIN_SCORE = 0.75
_NOT_LETTERS = re.compile(r"[^\w']+|_")

Value = tuple[float, int, int]  # (score sum, depth sum, -words)
ZERO: Value = (0.0, 0, 0)


def _add(a: Value, b: Value) -> Value:
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


class _Grid:
    """Best path through a 2-D grid of states ``(x, y)`` from ``(0, 0)`` to ``(nx, ny)``.

    Moves are skips (``x + 1`` or ``y + 1``, value 0) and caller-supplied jumps with a value.
    States are visited in row-major order and only a strictly better value replaces an earlier
    one, so ties keep the first path found (deterministic)."""

    def __init__(self, nx: int, ny: int) -> None:
        self.nx, self.ny = nx, ny
        self.value: list[list[Value | None]] = [[None] * (ny + 1) for _ in range(nx + 1)]
        self.back: list[list[tuple[int, int, object] | None]] = [[None] * (ny + 1) for _ in range(nx + 1)]
        self.value[0][0] = ZERO

    def relax(self, x: int, y: int, value: Value, frm: tuple[int, int], payload: object) -> None:
        current = self.value[x][y]
        if current is None or value > current:
            self.value[x][y] = value
            self.back[x][y] = (frm[0], frm[1], payload)

    def path(self) -> tuple[Value, list[object]]:
        x, y = self.nx, self.ny
        value = self.value[x][y] or ZERO
        payloads: list[object] = []
        while (x, y) != (0, 0):
            back = self.back[x][y]
            if back is None:
                break
            px, py, payload = back
            if payload is not None:
                payloads.append(payload)
            x, y = px, py
        payloads.reverse()
        return value, payloads


class _Detector:
    """Spans get integer ids; pair values live in ``scores[(i, j)]`` (both orders)."""

    def __init__(self, lines: Sequence[LineWords], spans: dict[int, tuple[PatternSpan, ...]], scorer: PairScorer) -> None:
        self.lines = [line.line_index for line in lines if line.line_index in spans]
        self.word_counts = {line.line_index: len(line.words) for line in lines}
        self.all: list[PatternSpan] = [span for line in self.lines for span in spans[line]]
        self.by_line: dict[int, list[int]] = {}
        for i, span in enumerate(self.all):
            self.by_line.setdefault(span.pos.line_index, []).append(i)
        self.scorer = scorer
        self.scores: dict[tuple[int, int], Value] = {}
        self.matches: dict[tuple[int, int], list[tuple[int, int]]] = {}
        self._score_pairs()

    def _score_pairs(self) -> None:
        signatures = [self.scorer.signature(span) for span in self.all]
        words = [last_word_key(span) for span in self.all]
        # per line: last nucleus -> signature -> span ids (only vowel-neighbour buckets can rhyme)
        buckets: dict[int, dict[str, dict[TailSignature, list[int]]]] = {}
        for line in self.lines:
            by_nucleus: dict[str, dict[TailSignature, list[int]]] = {}
            for i in self.by_line[line]:
                sig = signatures[i]
                by_nucleus.setdefault(sig.nuclei[0], {}).setdefault(sig, []).append(i)
            buckets[line] = by_nucleus
        for x, la in enumerate(self.lines):
            for lb in self.lines[x + 1 :]:
                found: list[tuple[int, int]] = []
                for nucleus, groups_a in buckets[la].items():
                    for neighbour in vowel_neighbours(nucleus):
                        groups_b = buckets[lb].get(neighbour)
                        if not groups_b:
                            continue
                        for sig_a, members_a in groups_a.items():
                            for sig_b, members_b in groups_b.items():
                                result = self.scorer.pair_signatures(sig_a, sig_b)
                                if result is None:
                                    continue
                                depth = min(sig_a.k, sig_b.k)
                                for i in members_a:
                                    for j in members_b:
                                        if words[i] == words[j]:
                                            continue
                                        value: Value = (result, depth, -(self.all[i].pos.word_count + self.all[j].pos.word_count))
                                        self.scores[(i, j)] = value
                                        self.scores[(j, i)] = value
                                        found.append((i, j))
                found.sort()
                self.matches[(la, lb)] = found

    def align_pair(self, la: int, lb: int) -> tuple[Value, list[tuple[int, int]]]:
        """Needleman-Wunsch over word positions (gap cost 0): ``best[p][q]`` is the best chain
        within the first ``p`` words of ``la`` and ``q`` words of ``lb``. Ties keep the skip of
        ``la`` first, then the skip of ``lb``, then the earliest match."""
        ending: dict[tuple[int, int], list[tuple[int, int]]] = {}
        for i, j in self.matches.get((la, lb), ()):
            ending.setdefault((self.all[i].pos.word_end, self.all[j].pos.word_end), []).append((i, j))
        if not ending:
            return ZERO, []
        na, nb = self.word_counts[la], self.word_counts[lb]
        best: list[list[Value]] = [[ZERO] * (nb + 1) for _ in range(na + 1)]
        back: list[list[tuple[int, int] | None]] = [[None] * (nb + 1) for _ in range(na + 1)]
        for p in range(na + 1):
            row, up = best[p], best[p - 1] if p else None
            for q in range(nb + 1):
                value, frm = ZERO, None
                if up is not None and up[q] > value:
                    value, frm = up[q], None
                if q and row[q - 1] > value:
                    value, frm = row[q - 1], None
                for i, j in ending.get((p, q), ()):
                    candidate = _add(best[self.all[i].pos.word_start][self.all[j].pos.word_start], self.scores[(i, j)])
                    if candidate > value:
                        value, frm = candidate, (i, j)
                row[q] = value
                back[p][q] = frm
        chain: list[tuple[int, int]] = []
        p, q = na, nb
        while p > 0 or q > 0:
            match = back[p][q]
            if match is not None:
                chain.append(match)
                p, q = self.all[match[0]].pos.word_start, self.all[match[1]].pos.word_start
            elif p > 0 and best[p - 1][q] == best[p][q]:
                p -= 1
            else:
                q -= 1
        chain.reverse()
        return best[na][nb], chain

    def fold(self, profile: list[dict[int, int]], line: int) -> None:
        """Join the line's spans to existing slots (slot order = word order on the line)."""
        starts: dict[int, list[int]] = {}
        for i in self.by_line[line]:
            starts.setdefault(self.all[i].pos.word_start, []).append(i)
        grid = _Grid(len(profile), self.word_counts[line])
        for s in range(grid.nx + 1):
            members = list(profile[s].values()) if s < grid.nx else []
            for p in range(grid.ny + 1):
                current = grid.value[s][p]
                if current is None:
                    continue
                if s < grid.nx:
                    for i in starts.get(p, ()):
                        value = self._join_value(i, members)
                        if value is not None:
                            grid.relax(s + 1, self.all[i].pos.word_end, _add(current, value), (s, p), (s, i))
                    grid.relax(s + 1, p, current, (s, p), None)
                if p < grid.ny:
                    grid.relax(s, p + 1, current, (s, p), None)
        _, path = grid.path()
        for entry in path:
            assert isinstance(entry, tuple)
            s, i = entry
            profile[s][line] = i

    def _join_value(self, i: int, members: Sequence[int]) -> Value | None:
        """Mean score against the slot's members, when it clears ``min_score`` against each."""
        total, depth = 0.0, 4
        for member in members:
            value = self.scores.get((i, member))
            if value is None:
                return None
            total += value[0]
            depth = min(depth, value[1])
        return (total / len(members), depth, -self.all[i].pos.word_count)

    def open_slots(self, profile: list[dict[int, int]], line: int, others: Sequence[int]) -> None:
        taken: dict[int, set[int]] = {}
        for slot in profile:
            for l, i in slot.items():
                taken.setdefault(l, set()).update(range(self.all[i].pos.word_start, self.all[i].pos.word_end))

        def free(i: int) -> bool:
            pos = self.all[i].pos
            words = taken.get(pos.line_index)
            return not words or all(w not in words for w in range(pos.word_start, pos.word_end))

        candidates: list[tuple[Value, int, int]] = []
        for other in others:
            key, flip = ((line, other), False) if line < other else ((other, line), True)
            for a, b in self.matches.get(key, ()):
                x, y = (b, a) if flip else (a, b)
                if free(x) and free(y):
                    candidates.append((self.scores[(x, y)], x, y))
        candidates.sort(key=lambda c: (-c[0][0], -c[0][1], -c[0][2], self.all[c[1]].pos, self.all[c[2]].pos))
        positions = [{l: self.all[i].pos for l, i in slot.items()} for slot in profile]
        for _value, x, y in candidates:
            if not (free(x) and free(y)):
                continue
            px, py = self.all[x].pos, self.all[y].pos
            position = _insertion_point(positions, (px, py))
            if position is not None:
                profile.insert(position, {px.line_index: x, py.line_index: y})
                positions.insert(position, {px.line_index: px, py.line_index: py})
                for pos in (px, py):
                    taken.setdefault(pos.line_index, set()).update(range(pos.word_start, pos.word_end))

    def run(self) -> list[list[PatternSpan]]:
        if len(self.lines) < 2:
            return []
        alignments = {
            (la, lb): self.align_pair(la, lb) for x, la in enumerate(self.lines) for lb in self.lines[x + 1 :]
        }
        (seed_a, seed_b), (_seed_value, seed_chain) = max(
            alignments.items(), key=lambda item: (item[1][0], -item[0][0], -item[0][1])
        )
        if not seed_chain:
            return []
        profile: list[dict[int, int]] = [{seed_a: i, seed_b: j} for i, j in seed_chain]
        placed = [seed_a, seed_b]
        remaining = [line for line in self.lines if line not in placed]

        def affinity(line: int) -> Value:
            return max(alignments[(min(line, p), max(line, p))][0] for p in placed)

        while remaining:
            line = max(remaining, key=lambda l: (affinity(l), -l))
            self.fold(profile, line)
            self.open_slots(profile, line, placed)
            placed.append(line)
            remaining.remove(line)
        return [[self.all[slot[line]] for line in sorted(slot)] for slot in profile]


def last_word_key(span: PatternSpan) -> str:
    """The span's last word, lowercased, without punctuation (apostrophes kept). The rhyme lives in
    the last word, so spans with equal keys ("not" / "it's not") repeat it rather than rhyme."""
    words = [w for w in (_NOT_LETTERS.sub("", word).strip("'") for word in span.unit.text.lower().split()) if w]
    return words[-1] if words else ""


def _group(ids: Sequence[int], signatures: Sequence[TailSignature]) -> dict[TailSignature, list[int]]:
    groups: dict[TailSignature, list[int]] = {}
    for i in ids:
        groups.setdefault(signatures[i], []).append(i)
    return groups


def _insertion_point(profile: Sequence[dict[int, SpanPos]], members: Sequence[SpanPos]) -> int | None:
    """The first index ``t`` where a slot of ``members`` keeps word order on each of their lines:
    every slot before ``t`` ends at or before the member on that line, every slot from ``t`` on
    starts at or after its end."""
    low, high = 0, len(profile)
    for m in members:
        for t, slot in enumerate(profile):
            other = slot.get(m.line_index)
            if other is None:
                continue
            if other.word_start < m.word_end:
                low = max(low, t + 1)  # must come after this slot
            if other.word_end > m.word_start:
                high = min(high, t)  # must come before this slot
    return low if low <= high else None


def detect(
    lines: Sequence[LineWords],
    *,
    dialect: DialectMap,
    weights: ScoreWeights | None = None,
    min_score: float = DETECT_MIN_SCORE,
    max_span_words: int = MAX_SPAN_WORDS,
    max_slots: int = MAX_SLOTS,
    snippet_id: str = "",
) -> tuple[PatternSlot, ...]:
    """The ordered slots of the pattern lines (see the module docstring)."""
    line_count = sum(1 for line in lines if line.words)
    spans = {line.line_index: line_spans(snippet_id, line, dialect, max_span_words) for line in lines}
    spans = {line: found for line, found in spans.items() if found}
    scorer = PairScorer(min_score, weights)
    groups = _Detector(lines, spans, scorer).run()
    slots = [summarize(members, scorer, line_count) for members in groups if len(members) >= 2]
    word_counts = {line.line_index: len(line.words) for line in lines}
    ordered = order_slots(slots, word_counts)
    return tuple(order_slots(cap_slots(ordered, max_slots), word_counts))
