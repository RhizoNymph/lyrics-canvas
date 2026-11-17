import { describe, it, expect } from 'vitest';
import { exportFallbackFilename, exportNotice, hasScore } from './exportFile';
import { ScoreApiError } from '@/types/scoreTools';
import type { Score } from '@/types/score';

const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 0,
  meters: [{ bar: 0, num: 4, den: 4 }],
  tempos: [{ bar: 0, bpm: 90 }],
  snap: '1/8',
  lines: [],
};

describe('exportFallbackFilename', () => {
  it('names the file after the snippet id with the format extension', () => {
    expect(exportFallbackFilename('0123456789abcdef', 'midi')).toBe('snippet-01234567.mid');
    expect(exportFallbackFilename('abc', 'musicxml')).toBe('snippet-abc.musicxml');
  });
});

describe('hasScore', () => {
  it('is true only for a snippet carrying a score object', () => {
    expect(hasScore({ score })).toBe(true);
    expect(hasScore({ score: null })).toBe(false);
    expect(hasScore({})).toBe(false);
  });
});

describe('exportNotice', () => {
  it('confirms a saved file', () => {
    expect(exportNotice({ ok: true, filename: 'a.mid' })).toEqual({ text: 'Saved a.mid', tone: 'info' });
  });

  it('describes a failure', () => {
    const notice = exportNotice({ ok: false, error: new ScoreApiError('score_not_found', 'export', 'x', 404) });
    expect(notice.tone).toBe('error');
    expect(notice.text).toMatch(/no score/i);
  });
});
