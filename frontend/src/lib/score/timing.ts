/** Bars, beats and time (spec/score_format.md sections 5-7). Pure functions of a valid Score. */
import { SCORE_WHOLE } from '@/types/score';
import type { Score, ScoreMeter, ScoreNote, ScoreTempo } from '@/types/score';
import { ScoreArgumentError } from './errors';
import { meterBarLength } from './validate';

export const PICKUP_BAR = -1;

export interface BarPosition {
  readonly bar: number;
  readonly offset: number;
}

/** `M(bar)`: the last meter entry at or before `bar` (bar -1 uses the first). */
export function meterAtBar(score: Score, bar: number): ScoreMeter {
  let current = score.meters[0];
  for (const meter of score.meters) {
    if (meter.bar > bar) break;
    current = meter;
  }
  return current;
}

export function tempoAtBar(score: Score, bar: number): ScoreTempo {
  let current = score.tempos[0];
  for (const tempo of score.tempos) {
    if (tempo.bar > bar) break;
    current = tempo;
  }
  return current;
}

/** One denominator note in ticks. */
export function meterUnit(meter: Pick<ScoreMeter, 'den'>): number {
  return SCORE_WHOLE / meter.den;
}

export function barLength(score: Score, bar: number): number {
  if (bar < PICKUP_BAR) throw new ScoreArgumentError(`bar ${bar} < -1`);
  if (bar === PICKUP_BAR) return score.pickup;
  return meterBarLength(meterAtBar(score, bar));
}

export function barStartTick(score: Score, bar: number): number {
  if (bar < PICKUP_BAR) throw new ScoreArgumentError(`bar ${bar} < -1`);
  if (bar === PICKUP_BAR) return 0;
  let tick = score.pickup;
  const { meters } = score;
  for (let i = 0; i < meters.length; i++) {
    const meter = meters[i];
    const length = meterBarLength(meter);
    const segmentEnd = i + 1 < meters.length ? meters[i + 1].bar : null;
    if (segmentEnd === null || bar <= segmentEnd) return tick + (bar - meter.bar) * length;
    tick += (segmentEnd - meter.bar) * length;
  }
  throw new Error('unreachable: the last meter segment is unbounded');
}

export function barAtTick(score: Score, tick: number): BarPosition {
  if (tick < 0) throw new ScoreArgumentError(`tick ${tick} < 0`);
  if (tick < score.pickup) return { bar: PICKUP_BAR, offset: tick };
  let start = score.pickup;
  const { meters } = score;
  for (let i = 0; i < meters.length; i++) {
    const meter = meters[i];
    const length = meterBarLength(meter);
    if (i + 1 < meters.length) {
      const bars = meters[i + 1].bar - meter.bar;
      if (tick >= start + bars * length) {
        start += bars * length;
        continue;
      }
    }
    const into = Math.floor((tick - start) / length);
    return { bar: meter.bar + into, offset: tick - start - into * length };
  }
  throw new Error('unreachable: the last meter segment is unbounded');
}

export function meterAtTick(score: Score, tick: number): ScoreMeter {
  const position = barAtTick(score, tick);
  return position.bar === PICKUP_BAR ? score.meters[0] : meterAtBar(score, position.bar);
}

/** `WHOLE / den` of the meter in force at `tick` (the unit of the rest link). */
export function beatUnit(score: Score, tick: number): number {
  return meterUnit(meterAtTick(score, tick));
}

export function isCompound(meter: Pick<ScoreMeter, 'num' | 'den'>): boolean {
  return meter.num % 3 === 0 && meter.num >= 6 && meter.den >= 8;
}

/** 3 bar downbeat, 2 on a beat, 1 on a half beat (compound: a subdivision), 0 otherwise. */
export function metricWeight(score: Score, tick: number): 0 | 1 | 2 | 3 {
  const position = barAtTick(score, tick);
  let meter: ScoreMeter;
  let offset: number;
  if (position.bar === PICKUP_BAR) {
    meter = score.meters[0];
    offset = meterBarLength(meter) - score.pickup + tick;
  } else {
    meter = meterAtBar(score, position.bar);
    offset = position.offset;
  }
  if (offset === 0) return 3;
  const unit = meterUnit(meter);
  const [beat, sub] = isCompound(meter) ? [3 * unit, unit] : [unit, unit / 2];
  if (offset % beat === 0) return 2;
  if (offset % sub === 0) return 1;
  return 0;
}

/** Milliseconds from tick 0, by the exact summation of spec section 7. */
export function tickToMs(score: Score, tick: number): number {
  if (tick < 0) throw new ScoreArgumentError(`tick ${tick} < 0`);
  const starts = [0, ...score.tempos.slice(1).map((tempo) => barStartTick(score, tempo.bar))];
  let ms = 0.0;
  for (let i = 0; i < score.tempos.length; i++) {
    const start = starts[i];
    if (tick <= start) break;
    const end = i + 1 < starts.length ? starts[i + 1] : null;
    const ticks = end === null ? tick - start : Math.min(tick, end) - start;
    ms = ms + (ticks * 60000.0) / (score.tempos[i].bpm * 48.0);
  }
  return ms;
}

export function noteEnd(note: Pick<ScoreNote, 't' | 'd'>): number {
  return note.t + note.d;
}

export function endTick(score: Score): number {
  let end = 0;
  for (const line of score.lines) for (const note of line.notes) end = Math.max(end, noteEnd(note));
  return end;
}

/** Bars from bar 0 needed to hold every note, at least 1. */
export function barCount(score: Score): number {
  const position = barAtTick(score, endTick(score));
  if (position.bar === PICKUP_BAR) return 1;
  return Math.max(1, position.bar + (position.offset > 0 ? 1 : 0));
}
