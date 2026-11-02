"""Voice-take line assembly: timed words (+ the user's tap breaks) -> lyric lines. Pure; no I/O.

Two modes:

- **Taps.** Each break (ms since recording start) splits at the inter-word boundary nearest to it.
  Boundary ``i`` sits between word ``i`` and word ``i + 1``, at the midpoint of the gap between word
  ``i``'s end and word ``i + 1``'s start. Taps usually land slightly late, so a tie goes to the
  earlier boundary. Breaks at or before the first word's start, or at or after the last word's end,
  are ignored; breaks resolving to the same boundary count once. Lines are never empty.
- **Auto** (no break resolved to a boundary). A dynamic program over the boundaries maximises
  ``sum(boundary score) + sum(end-rhyme bonus between consecutive lines) - sum(line cost)``, run once
  per target line length ``T`` (so lines are balanced against a common length), and is compared with
  keeping the take as one line. Short takes, and takes where no split beats one line, stay single.

All scoring weights are named constants below and collected in ``AutoSplitParams``; the rationale and
worked examples are in docs/features/voice_capture.md ("Auto-split scoring").
"""

from __future__ import annotations

import re
import statistics
from bisect import bisect_right
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from app.models.recording import SplitMode

# ---- auto-split scoring (see docs/features/voice_capture.md) ----

# Boundary score = PAUSE_WEIGHT * pause_strength - SPLIT_COST, where pause_strength is the gap's excess
# over the take's median (low) inter-word gap, divided by PAUSE_SATURATION_MS and capped at 1. A split
# where the speaker did not pause costs SPLIT_COST; a pause of PAUSE_SATURATION_MS beyond the speaker's
# normal gap earns PAUSE_WEIGHT - SPLIT_COST = +2.
PAUSE_WEIGHT = 3.0
PAUSE_SATURATION_MS = 600
SPLIT_COST = 1.0
# Line cost = BALANCE_WEIGHT * |syllables - T| / T for the run's target length T, plus the length
# penalties below. Two lines differing by half the longer one's length cost 0.75.
BALANCE_WEIGHT = 1.5
# End-rhyme bonus between consecutive lines' last words = RHYME_WEIGHT * engine score (k = 2) when the
# score reaches RHYME_MIN_SCORE, else 0; the same word repeated earns IDENTICAL_RHYME_FACTOR of that.
RHYME_WEIGHT = 1.0
RHYME_MIN_SCORE = 0.75
IDENTICAL_RHYME_FACTOR = 0.5
# Lines under MIN_LINE_SYLLABLES cost SHORT_LINE_PENALTY per missing syllable; lines over
# MAX_LINE_SYLLABLES cost OVERLONG_PENALTY per extra syllable (the one-line option pays this too).
# Targets T range over MIN..MAX. Lines over HARD_MAX_LINE_SYLLABLES are not considered (one word is
# always allowed to be a line, whatever its length).
MIN_LINE_SYLLABLES = 4
SHORT_LINE_PENALTY = 0.5
MAX_LINE_SYLLABLES = 14
OVERLONG_PENALTY = 0.25
HARD_MAX_LINE_SYLLABLES = 32
# Takes under SHORT_TAKE_SYLLABLES syllables or MIN_WORDS_TO_SPLIT words are never auto split.
SHORT_TAKE_SYLLABLES = 10
MIN_WORDS_TO_SPLIT = 4

_EPSILON = 1e-9
_LETTERS = re.compile(r"[^a-z']")


class LineAssemblyError(Exception):
    """The input cannot be assembled into lines (no words) or the parameters are invalid."""


@dataclass(frozen=True, slots=True)
class TimedWord:
    """One transcribed word; times in ms since the recording started."""

    text: str
    start_ms: int
    end_ms: int


@dataclass(frozen=True, slots=True)
class AssembledLines:
    lines: tuple[str, ...]  # at least one, none blank
    split_mode: SplitMode


class WordOracle(Protocol):
    """What the auto split needs from the phonetic engine."""

    def syllables(self, word: str) -> int:
        """Syllables of one word as the beat grid would count them (0 for no letters)."""
        ...

    def end_rhyme(self, a: str, b: str) -> float:
        """How well two line-final words rhyme, in [0, 1]."""
        ...


@dataclass(frozen=True, slots=True)
class AutoSplitParams:
    pause_weight: float = PAUSE_WEIGHT
    pause_saturation_ms: int = PAUSE_SATURATION_MS
    split_cost: float = SPLIT_COST
    balance_weight: float = BALANCE_WEIGHT
    rhyme_weight: float = RHYME_WEIGHT
    rhyme_min_score: float = RHYME_MIN_SCORE
    identical_rhyme_factor: float = IDENTICAL_RHYME_FACTOR
    min_line_syllables: int = MIN_LINE_SYLLABLES
    short_line_penalty: float = SHORT_LINE_PENALTY
    max_line_syllables: int = MAX_LINE_SYLLABLES
    overlong_penalty: float = OVERLONG_PENALTY
    hard_max_line_syllables: int = HARD_MAX_LINE_SYLLABLES
    short_take_syllables: int = SHORT_TAKE_SYLLABLES
    min_words_to_split: int = MIN_WORDS_TO_SPLIT

    def __post_init__(self) -> None:
        weights = (
            self.pause_weight, self.split_cost, self.balance_weight, self.rhyme_weight,
            self.identical_rhyme_factor, self.short_line_penalty, self.overlong_penalty,
        )
        if min(weights) < 0:
            raise LineAssemblyError("auto-split weights must be non-negative")
        if self.pause_saturation_ms <= 0:
            raise LineAssemblyError("pause_saturation_ms must be positive")
        if not 1 <= self.min_line_syllables <= self.max_line_syllables <= self.hard_max_line_syllables:
            raise LineAssemblyError("need 1 <= min_line_syllables <= max_line_syllables <= hard_max_line_syllables")
        if self.min_words_to_split < 2:
            raise LineAssemblyError("min_words_to_split must be at least 2")


DEFAULT_PARAMS = AutoSplitParams()


# ---- taps ----


def tap_boundaries(words: Sequence[TimedWord], breaks_ms: Sequence[int]) -> list[int]:
    """The boundaries (index ``i`` = after word ``i``) the breaks resolve to, ascending and unique.

    A break in the pause after word ``i`` splits there, however long the pause. A break inside a word
    splits before it when it lands in the word's first half (the writer tapped late, as the next line
    began; an exact half counts as first) and after it otherwise. When timestamps overlap, the word
    that started last owns the instant. Breaks that would split before the first word or after the
    last are ignored.
    """
    if len(words) < 2:
        return []
    starts = [w.start_ms for w in words]
    monotone = all(a <= b for a, b in zip(starts, starts[1:]))
    last = len(words) - 1
    chosen: set[int] = set()
    for brk in breaks_ms:
        i = _owner(starts, brk, monotone)
        if i is None:
            continue
        word = words[i]
        if brk < word.end_ms and 2 * (brk - word.start_ms) <= word.end_ms - word.start_ms:
            boundary = i - 1
        else:
            boundary = i
        if 0 <= boundary < last:
            chosen.add(boundary)
    return sorted(chosen)


def _owner(starts: Sequence[int], at_ms: int, monotone: bool) -> int | None:
    """Index of the last word starting at or before ``at_ms`` (latest start on overlap), if any."""
    if monotone:
        index = bisect_right(starts, at_ms) - 1
        return index if index >= 0 else None
    started = [i for i, start in enumerate(starts) if start <= at_ms]
    return max(started, key=lambda i: (starts[i], i)) if started else None


def lines_from_boundaries(words: Sequence[TimedWord], boundaries: Sequence[int]) -> tuple[str, ...]:
    """Join the words into lines, splitting after each boundary index (ascending, in range)."""
    lines: list[str] = []
    start = 0
    for boundary in [*boundaries, len(words) - 1]:
        lines.append(" ".join(w.text for w in words[start : boundary + 1]))
        start = boundary + 1
    return tuple(lines)


# ---- auto split ----


def pause_strengths(words: Sequence[TimedWord], params: AutoSplitParams = DEFAULT_PARAMS) -> list[float]:
    """Per boundary, in [0, 1]: how much longer than the take's median (low) gap the gap is."""
    gaps = [max(0, words[i + 1].start_ms - words[i].end_ms) for i in range(len(words) - 1)]
    if not gaps:
        return []
    baseline = statistics.median_low(gaps)
    return [min(1.0, max(0, gap - baseline) / params.pause_saturation_ms) for gap in gaps]


def _normalized(word: str) -> str:
    return _LETTERS.sub("", word.lower())


def _line_cost(syllables: int, target: int, params: AutoSplitParams) -> float:
    cost = params.balance_weight * abs(syllables - target) / target
    if syllables < params.min_line_syllables:
        cost += params.short_line_penalty * (params.min_line_syllables - syllables)
    if syllables > params.max_line_syllables:
        cost += params.overlong_penalty * (syllables - params.max_line_syllables)
    return cost


class _Scorer:
    """Precomputed per-take terms shared by every target length."""

    def __init__(self, words: Sequence[TimedWord], oracle: WordOracle, params: AutoSplitParams):
        self.words = words
        self.oracle = oracle
        self.params = params
        syllables = [max(0, oracle.syllables(w.text)) for w in words]
        self.prefix = [0]
        for count in syllables:
            self.prefix.append(self.prefix[-1] + count)
        self.boundary = [params.pause_weight * s - params.split_cost for s in pause_strengths(words, params)]
        self._rhyme: dict[tuple[int, int], float] = {}

    @property
    def total(self) -> int:
        return self.prefix[-1]

    def syllables(self, first: int, last: int) -> int:
        return self.prefix[last + 1] - self.prefix[first]

    def rhyme(self, i: int, j: int) -> float:
        """Bonus for the line ending at word ``i`` followed by the line ending at word ``j``."""
        key = (i, j)
        cached = self._rhyme.get(key)
        if cached is not None:
            return cached
        a, b = self.words[i].text, self.words[j].text
        score = self.oracle.end_rhyme(a, b)
        bonus = 0.0
        if score >= self.params.rhyme_min_score:
            same = _normalized(a) == _normalized(b) and _normalized(a) != ""
            bonus = self.params.rhyme_weight * score * (self.params.identical_rhyme_factor if same else 1.0)
        self._rhyme[key] = bonus
        return bonus

    def best_multi_line(self, target: int) -> tuple[float, list[int]]:
        """Best segmentation into two or more lines for one target length; (-inf, []) if none."""
        n = len(self.words)
        hard_max = self.params.hard_max_line_syllables
        best = [float("-inf")] * n  # best[b]: words 0..b segmented, a line ending at b
        back = [-1] * n  # previous line's last word, -1 = the line starts at word 0
        for last in range(n):
            for first in range(last, -1, -1):
                count = self.syllables(first, last)
                if first < last and count > hard_max:
                    break
                if first == 0 and last == n - 1:
                    continue  # the one-line option is scored separately
                cost = _line_cost(count, target, self.params)
                if first == 0:
                    candidate = -cost
                else:
                    prev = first - 1
                    if best[prev] == float("-inf"):
                        continue
                    candidate = best[prev] + self.boundary[prev] + self.rhyme(prev, last) - cost
                if candidate > best[last] + _EPSILON:
                    best[last] = candidate
                    back[last] = first - 1
        if best[n - 1] == float("-inf"):
            return float("-inf"), []
        boundaries: list[int] = []
        at = back[n - 1]
        while at >= 0:
            boundaries.append(at)
            at = back[at]
        return best[n - 1], sorted(boundaries)


def auto_boundaries(
    words: Sequence[TimedWord], oracle: WordOracle, params: AutoSplitParams = DEFAULT_PARAMS
) -> list[int]:
    """The auto split's boundaries; [] keeps the take as one line."""
    if len(words) < params.min_words_to_split:
        return []
    scorer = _Scorer(words, oracle, params)
    if scorer.total < params.short_take_syllables:
        return []
    best_score = -_line_cost(scorer.total, scorer.total, params) if scorer.total > 0 else 0.0
    best: list[int] = []
    for target in range(params.min_line_syllables, params.max_line_syllables + 1):
        score, boundaries = scorer.best_multi_line(target)
        if boundaries and score > best_score + _EPSILON:
            best_score, best = score, boundaries
    return best


# ---- entry point ----


def assemble_lines(
    words: Sequence[TimedWord],
    breaks_ms: Sequence[int],
    oracle: WordOracle,
    params: AutoSplitParams = DEFAULT_PARAMS,
) -> AssembledLines:
    """Lines for a take: by taps when any break resolves to a boundary, else by the auto split."""
    cleaned = [TimedWord(w.text.strip(), w.start_ms, w.end_ms) for w in words if w.text.strip()]
    if not cleaned:
        raise LineAssemblyError("no words to assemble")
    taps = tap_boundaries(cleaned, breaks_ms)
    if taps:
        return AssembledLines(lines_from_boundaries(cleaned, taps), SplitMode.taps)
    auto = auto_boundaries(cleaned, oracle, params)
    if auto:
        return AssembledLines(lines_from_boundaries(cleaned, auto), SplitMode.auto)
    return AssembledLines(lines_from_boundaries(cleaned, []), SplitMode.single)
