"""Quantize sung syllable times to ticks (docs/features/score_draft_export.md#4-quantization).

Onsets are placed one after another: each onset's position is predicted from the previous placed
onset plus its interval at the (followed) tempo, then snapped to the lattice of its beat, either
straight sixteenths (12 ticks) or sixteenth triplets (8 ticks). A beat takes the triplet lattice
only when its onsets fit it clearly better. A tempo follower adjusts the tick length from the real
(word start) onsets, so a take that speeds up or slows down keeps its rhythm. Every onset lands
strictly after the previous one, so notes stay in order and never overlap.

The first onset is tick 0. Bar 0 starts at the first accented syllable that falls inside the first
bar (the syllables before it are the pickup); without one, bar 0 starts at the first onset.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass, field

from app.services.score import PPQ
from app.services.score_tools.draft.types import Grid, Quantized, QuantizedNote, SyllableTime

BEAT_TICKS = PPQ  # one quarter note: bpm counts quarter notes
TRIPLET_MARGIN = 2.0  # ticks per onset the triplet lattice must win by (after a straight beat)
TRIPLET_STAY_MARGIN = -1.0  # after a triplet beat the triplet lattice wins ties and near-ties
FOLLOW_RATE = 0.2  # how fast the tempo follower moves toward a measured interval
FOLLOW_RATIO_BOUNDS = (0.8, 1.25)  # a measured interval ratio is clamped to this
FOLLOW_SCALE_BOUNDS = (2 / 3, 1.5)  # the follower never drifts further than this from the bpm
FOLLOW_MAX_TICKS = 2 * BEAT_TICKS  # longer intervals (pauses) say nothing about the tempo


def _round(value: float) -> int:
    """Round half up (deterministic, unlike banker's rounding)."""
    return math.floor(value + 0.5)


def snap(position: float, grid: Grid, origin: int) -> int:
    step = grid.step
    return origin + _round((position - origin) / step) * step


def next_point_after(tick: int, grid: Grid, origin: int) -> int:
    step = grid.step
    return origin + (math.floor((tick - origin) / step) + 1) * step


def _lattice_error(relative: float, step: int) -> float:
    return abs(relative - step * _round(relative / step))


def choose_grid(relatives: Sequence[float], previous: Grid | None, triplets_allowed: bool) -> Grid:
    """The lattice for one beat from its onsets' predicted offsets into the beat (``[0, 48)``)."""
    if not triplets_allowed or not relatives:
        return Grid.straight
    if len(relatives) < 2 and previous is not Grid.triplet:
        return Grid.straight
    margin = TRIPLET_STAY_MARGIN if previous is Grid.triplet else TRIPLET_MARGIN
    straight = sum(_lattice_error(r, Grid.straight.step) for r in relatives)
    triplet = sum(_lattice_error(r, Grid.triplet.step) for r in relatives)
    return Grid.triplet if triplet + margin * len(relatives) < straight else Grid.straight


@dataclass(slots=True)
class _Pass:
    """One quantization pass with the lattice origin (bar 0 start) at ``origin``."""

    syllables: Sequence[SyllableTime]
    tick_ms: float  # nominal ms per tick at the bpm
    origin: int
    triplets_allowed: bool
    ticks: list[int] = field(default_factory=list)
    grids: list[Grid] = field(default_factory=list)
    scales: list[float] = field(default_factory=list)
    modes: dict[int, Grid] = field(default_factory=dict)
    scale: float = 1.0

    def predict(self, index: int) -> float:
        previous = index - 1
        return self.ticks[previous] + (self.syllables[index].start_ms - self.syllables[previous].start_ms) / (
            self.tick_ms * self.scale
        )

    def beat_of(self, position: float) -> int:
        return math.floor((position - self.origin) / BEAT_TICKS)

    def _decide(self, beat: int, index: int) -> Grid:
        start = self.origin + beat * BEAT_TICKS
        base_tick, base_ms = self.ticks[index - 1], self.syllables[index - 1].start_ms
        relatives: list[float] = []
        for later in self.syllables[index:]:
            position = base_tick + (later.start_ms - base_ms) / (self.tick_ms * self.scale)
            if position >= start + BEAT_TICKS:
                break
            relatives.append(position - start)
        return choose_grid(relatives, self.modes.get(beat - 1), self.triplets_allowed)

    def _follow(self, index: int, tick: int) -> None:
        current = self.syllables[index]
        if not current.word_start:
            return
        for earlier in range(index - 1, -1, -1):
            if self.syllables[earlier].word_start:
                ticks = tick - self.ticks[earlier]
                ms = current.start_ms - self.syllables[earlier].start_ms
                if 0 < ticks <= FOLLOW_MAX_TICKS and ms > 0:
                    low, high = FOLLOW_RATIO_BOUNDS
                    ratio = min(high, max(low, ms / (ticks * self.tick_ms * self.scale)))
                    scale = self.scale * (1 + FOLLOW_RATE * (ratio - 1))
                    self.scale = min(FOLLOW_SCALE_BOUNDS[1], max(FOLLOW_SCALE_BOUNDS[0], scale))
                return

    def run(self) -> None:
        for index in range(len(self.syllables)):
            if index == 0:
                tick, grid = 0, Grid.straight
            else:
                position = self.predict(index)
                beat = self.beat_of(position)
                if beat not in self.modes:
                    self.modes[beat] = self._decide(beat, index)
                grid = self.modes[beat]
                tick = snap(position, grid, self.origin)
                if tick <= self.ticks[-1]:
                    tick = next_point_after(self.ticks[-1], grid, self.origin)
            self.scales.append(self.scale)
            self.ticks.append(tick)
            self.grids.append(grid)
            self._follow(index, tick)

    def durations(self) -> list[int]:
        out: list[int] = []
        count = len(self.syllables)
        for index, syllable in enumerate(self.syllables):
            tick_ms = self.tick_ms * self.scales[index]
            step = self.grids[index].step
            if index + 1 < count:
                room = self.ticks[index + 1] - self.ticks[index]
                if self.syllables[index + 1].start_ms - syllable.end_ms < step * tick_ms / 2:
                    out.append(room)  # legato: sung up to the next onset
                    continue
            length = max(step, _round((syllable.end_ms - syllable.start_ms) / tick_ms / step) * step)
            out.append(min(length, room) if index + 1 < count else length)
        return out

    def beat_counts(self) -> tuple[int, int]:
        triplet = sum(1 for mode in self.modes.values() if mode is Grid.triplet)
        return triplet, len(self.modes) - triplet


def _run(syllables: Sequence[SyllableTime], tick_ms: float, origin: int, triplets_allowed: bool) -> _Pass:
    done = _Pass(syllables, tick_ms, origin, triplets_allowed)
    done.run()
    return done


def _anchor(syllables: Sequence[SyllableTime], ticks: Sequence[int], bar_length: int) -> int | None:
    """Index of the first accented syllable inside the first bar, None when it is the first onset
    or there is none."""
    for index, (syllable, tick) in enumerate(zip(syllables, ticks)):
        if tick >= bar_length:
            return None
        if syllable.accent >= 1:
            return index if index > 0 else None
    return None


def quantize(syllables: Sequence[SyllableTime], bpm: float, bar_length: int) -> Quantized:
    """Ticks, durations and pickup for ``syllables`` (score order, non-decreasing starts)."""
    if not syllables:
        return Quantized(notes=(), pickup=0, triplet_beats=0, straight_beats=0)
    tick_ms = 60000.0 / bpm / BEAT_TICKS
    triplets_allowed = bar_length % Grid.triplet.step == 0
    first = _run(syllables, tick_ms, 0, triplets_allowed)
    chosen, pickup = first, 0
    anchor = _anchor(syllables, first.ticks, bar_length)
    if anchor is not None:
        second = _run(syllables, tick_ms, first.ticks[anchor], triplets_allowed)
        if 0 < second.ticks[anchor] < bar_length:
            chosen, pickup = second, second.ticks[anchor]
    triplet_beats, straight_beats = chosen.beat_counts()
    notes = tuple(QuantizedNote(t, d) for t, d in zip(chosen.ticks, chosen.durations()))
    return Quantized(notes=notes, pickup=pickup, triplet_beats=triplet_beats, straight_beats=straight_beats)
