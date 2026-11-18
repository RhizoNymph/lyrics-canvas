/**
 * The shared score vectors (spec/score_vectors.json at the repo root), read in place. Every case of every
 * section must pass; outputs are compared as JSON values (numbers numerically, key order ignored).
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import {
  applyGridRestEdit,
  applyRestLink,
  barAtTick,
  barCount,
  barLength,
  barStartTick,
  beatUnit,
  checkScore,
  draftFromGrid,
  endTick,
  isRestOnlyEdit,
  lcsPairs,
  lineKey,
  metricWeight,
  normalizeWord,
  reconcile,
  spliceLineTexts,
  tickToMs,
  validateScore,
  wordKey,
  NotRestOnlyEdit,
  ScoreInvalid,
  ScoreTextMismatch,
} from './index';
import type { GridJson } from './gridView';
import { SCORE_MAX_BAR, SCORE_MAX_TICKS, SCORE_PPQ, SCORE_SNAP_TICKS, SCORE_WHOLE } from '@/types/score';

const VECTORS_URL = new URL('../../../../spec/score_vectors.json', import.meta.url);
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Any = any;
const vectors = JSON.parse(readFileSync(VECTORS_URL, 'utf8')) as Record<string, Any>;

const SECTIONS = [
  'validate',
  'bars',
  'bar_at_tick',
  'beat_unit',
  'metric_weight',
  'tick_to_ms',
  'normalize_word',
  'lcs',
  'line_key',
  'text_splice',
  'rest_link',
  'rest_only_edit',
  'grid_rest_edit',
  'reconcile',
  'draft_from_grid',
];

function cases(section: string): Any[] {
  return vectors[section] as Any[];
}

function invalidBody(error: unknown): Record<string, unknown> {
  if (!(error instanceof ScoreInvalid)) throw error;
  return { error: 'invalid_score', reason: error.reason };
}

function capture<T>(fn: () => T): { ok: T } | { err: unknown } {
  try {
    return { ok: fn() };
  } catch (err) {
    return { err };
  }
}

describe('score vectors file', () => {
  it('is version 1 with every section', () => {
    expect(vectors.version).toBe(1);
    for (const section of SECTIONS) expect(cases(section).length, section).toBeGreaterThan(0);
  });

  it('agrees on the constants', () => {
    const c = vectors.constants;
    expect([c.ppq, c.whole, c.max_bar, c.max_ticks]).toEqual([SCORE_PPQ, SCORE_WHOLE, SCORE_MAX_BAR, SCORE_MAX_TICKS]);
    expect(c.snap_ticks).toEqual(SCORE_SNAP_TICKS);
  });
});

describe('validate', () => {
  for (const c of cases('validate')) {
    it(c.name, () => {
      const result = validateScore(c.score, c.grid as GridJson);
      if (result.ok) {
        expect(c.expect).toEqual({ ok: true });
      } else {
        const body: Record<string, unknown> = { ok: false, reason: result.error.reason };
        if (result.error.line !== undefined) body.line = result.error.line;
        if (result.error.note !== undefined) body.note = result.error.note;
        expect(body).toEqual(c.expect);
      }
    });
  }
});

describe('bars', () => {
  for (const c of cases('bars')) {
    it(c.name, () => {
      const score = checkScore(c.score, c.grid as GridJson);
      for (const bar of c.bars) {
        expect(barStartTick(score, bar.bar), `start ${bar.bar}`).toBe(bar.start);
        expect(barLength(score, bar.bar), `length ${bar.bar}`).toBe(bar.length);
      }
      expect(endTick(score)).toBe(c.end_tick);
      expect(barCount(score)).toBe(c.bar_count);
    });
  }
});

describe('bar_at_tick', () => {
  for (const c of cases('bar_at_tick')) {
    it(c.name, () => {
      const score = checkScore(c.score, null);
      for (const q of c.queries) expect(barAtTick(score, q.tick), `tick ${q.tick}`).toEqual({ bar: q.bar, offset: q.offset });
    });
  }
});

describe('beat_unit', () => {
  for (const c of cases('beat_unit')) {
    it(c.name, () => {
      const score = checkScore(c.score, null);
      for (const q of c.queries) expect(beatUnit(score, q.tick), `tick ${q.tick}`).toBe(q.beat_unit);
    });
  }
});

describe('metric_weight', () => {
  for (const c of cases('metric_weight')) {
    it(c.name, () => {
      const score = checkScore(c.score, null);
      for (const q of c.queries) expect(metricWeight(score, q.tick), `tick ${q.tick}`).toBe(q.weight);
    });
  }
});

describe('tick_to_ms', () => {
  for (const c of cases('tick_to_ms')) {
    it(c.name, () => {
      const score = checkScore(c.score, null);
      for (const q of c.queries) expect(Math.abs(tickToMs(score, q.tick) - q.ms), `tick ${q.tick}`).toBeLessThanOrEqual(1e-6);
    });
  }
});

describe('normalize_word', () => {
  for (const c of cases('normalize_word')) {
    it(c.name, () => {
      expect(normalizeWord(c.text)).toBe(c.normalized);
      expect(wordKey(c.text)).toBe(c.key);
    });
  }
});

describe('lcs', () => {
  for (const c of cases('lcs')) {
    it(c.name, () => {
      expect(lcsPairs(c.a, c.b)).toEqual(c.pairs);
    });
  }
});

describe('line_key', () => {
  for (const c of cases('line_key')) {
    it(c.name, () => {
      expect(lineKey(c.line)).toBe(c.key);
    });
  }
});

describe('text_splice', () => {
  for (const c of cases('text_splice')) {
    it(c.name, () => {
      const replacements = new Map<number, string>(
        Object.entries(c.replacements as Record<string, string>).map(([k, v]) => [Number(k), v]),
      );
      const result = capture(() => spliceLineTexts(c.raw_text, replacements));
      if ('ok' in result) expect({ text: result.ok }).toEqual(c.expect);
      else {
        expect(result.err).toBeInstanceOf(ScoreTextMismatch);
        expect(c.expect).toEqual({ error: 'text_mismatch' });
      }
    });
  }
});

describe('rest_link', () => {
  for (const c of cases('rest_link')) {
    it(c.name, () => {
      const result = capture(() => applyRestLink(checkScore(c.score, c.grid), c.grid as GridJson));
      if ('ok' in result) expect({ grid: result.ok }).toEqual(c.expect);
      else expect(invalidBody(result.err)).toEqual(c.expect);
    });
  }
});

describe('rest_only_edit', () => {
  for (const c of cases('rest_only_edit')) {
    it(c.name, () => {
      expect(isRestOnlyEdit(c.old_grid, c.new_grid)).toBe(c.expect);
    });
  }
});

describe('grid_rest_edit', () => {
  for (const c of cases('grid_rest_edit')) {
    it(c.name, () => {
      const result = capture(() => applyGridRestEdit(checkScore(c.score, c.old_grid), c.old_grid, c.new_grid));
      if ('ok' in result) expect({ score: result.ok.score, grid: result.ok.grid }).toEqual(c.expect);
      else if (result.err instanceof NotRestOnlyEdit) expect(c.expect).toEqual({ error: 'not_rest_only' });
      else expect(invalidBody(result.err)).toEqual(c.expect);
    });
  }
});

describe('reconcile', () => {
  for (const c of cases('reconcile')) {
    it(c.name, () => {
      const result = capture(() => reconcile(checkScore(c.score, c.old_grid), c.old_grid, c.new_grid));
      if ('ok' in result) expect({ score: result.ok }).toEqual(c.expect);
      else expect(invalidBody(result.err)).toEqual(c.expect);
    });
  }
});

describe('draft_from_grid', () => {
  for (const c of cases('draft_from_grid')) {
    it(c.name, () => {
      const result = capture(() => draftFromGrid(c.grid, c.meter, c.bpm, c.note));
      if ('ok' in result) expect({ score: result.ok }).toEqual(c.expect);
      else expect(invalidBody(result.err)).toEqual(c.expect);
    });
  }
});
