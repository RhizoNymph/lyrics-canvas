"""Bars, beats and time (spec/score_format.md sections 5-7). Pure functions of a valid Score."""

from __future__ import annotations

from dataclasses import dataclass

from app.services.score.errors import ScoreArgumentError
from app.services.score.types import Meter, Score, Tempo

PICKUP_BAR = -1


@dataclass(frozen=True, slots=True)
class BarPosition:
    bar: int
    offset: int


def meter_at_bar(score: Score, bar: int) -> Meter:
    """``M(bar)``: the last meter entry at or before ``bar`` (bar -1 uses the first)."""
    current = score.meters[0]
    for meter in score.meters:
        if meter.bar > bar:
            break
        current = meter
    return current


def tempo_at_bar(score: Score, bar: int) -> Tempo:
    current = score.tempos[0]
    for tempo in score.tempos:
        if tempo.bar > bar:
            break
        current = tempo
    return current


def bar_length(score: Score, bar: int) -> int:
    if bar < PICKUP_BAR:
        raise ScoreArgumentError(f"bar {bar} < -1")
    if bar == PICKUP_BAR:
        return score.pickup
    return meter_at_bar(score, bar).bar_length


def bar_start_tick(score: Score, bar: int) -> int:
    if bar < PICKUP_BAR:
        raise ScoreArgumentError(f"bar {bar} < -1")
    if bar == PICKUP_BAR:
        return 0
    tick = score.pickup
    meters = score.meters
    for index, meter in enumerate(meters):
        segment_end = meters[index + 1].bar if index + 1 < len(meters) else None
        if segment_end is None or bar <= segment_end:
            return tick + (bar - meter.bar) * meter.bar_length
        tick += (segment_end - meter.bar) * meter.bar_length
    raise AssertionError("unreachable: the last meter segment is unbounded")


def bar_at_tick(score: Score, tick: int) -> BarPosition:
    if tick < 0:
        raise ScoreArgumentError(f"tick {tick} < 0")
    if tick < score.pickup:
        return BarPosition(PICKUP_BAR, tick)
    start = score.pickup
    meters = score.meters
    for index, meter in enumerate(meters):
        length = meter.bar_length
        if index + 1 < len(meters):
            bars = meters[index + 1].bar - meter.bar
            if tick < start + bars * length:
                into = (tick - start) // length
                return BarPosition(meter.bar + into, tick - start - into * length)
            start += bars * length
        else:
            into = (tick - start) // length
            return BarPosition(meter.bar + into, tick - start - into * length)
    raise AssertionError("unreachable: the last meter segment is unbounded")


def meter_at_tick(score: Score, tick: int) -> Meter:
    position = bar_at_tick(score, tick)
    return score.meters[0] if position.bar == PICKUP_BAR else meter_at_bar(score, position.bar)


def beat_unit(score: Score, tick: int) -> int:
    return meter_at_tick(score, tick).unit


def is_compound(meter: Meter) -> bool:
    return meter.num % 3 == 0 and meter.num >= 6 and meter.den >= 8


def metric_weight(score: Score, tick: int) -> int:
    """3 bar downbeat, 2 on a beat, 1 on a half beat (compound: on a subdivision), 0 otherwise."""
    position = bar_at_tick(score, tick)
    if position.bar == PICKUP_BAR:
        meter = score.meters[0]
        offset = meter.bar_length - score.pickup + tick
    else:
        meter = meter_at_bar(score, position.bar)
        offset = position.offset
    if offset == 0:
        return 3
    unit = meter.unit
    if is_compound(meter):
        beat, sub = 3 * unit, unit
    else:
        beat, sub = unit, unit // 2
    if offset % beat == 0:
        return 2
    if offset % sub == 0:
        return 1
    return 0


def tick_to_ms(score: Score, tick: int) -> float:
    """Milliseconds from tick 0, by the exact summation of spec section 7."""
    if tick < 0:
        raise ScoreArgumentError(f"tick {tick} < 0")
    starts = [0] + [bar_start_tick(score, tempo.bar) for tempo in score.tempos[1:]]
    ms = 0.0
    for index, tempo in enumerate(score.tempos):
        start = starts[index]
        if tick <= start:
            break
        end = starts[index + 1] if index + 1 < len(starts) else None
        ticks = tick - start if end is None else min(tick, end) - start
        ms = ms + (float(ticks) * 60000.0) / (float(tempo.bpm) * 48.0)
    return ms


def end_tick(score: Score) -> int:
    return max((note.end for note in score.all_notes()), default=0)


def bar_count(score: Score) -> int:
    end = end_tick(score)
    position = bar_at_tick(score, end)
    if position.bar == PICKUP_BAR:
        return 1
    bars = position.bar + (1 if position.offset > 0 else 0)
    return max(1, bars)
