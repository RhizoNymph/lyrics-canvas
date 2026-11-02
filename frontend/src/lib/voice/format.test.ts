import { describe, it, expect } from 'vitest';
import { formatDuration, formatRecordedAt, transcriptPreview } from './format';

describe('formatDuration', () => {
  it('formats m:ss and h:mm:ss', () => {
    expect(formatDuration(0)).toBe('0:00');
    expect(formatDuration(999)).toBe('0:00');
    expect(formatDuration(61_234)).toBe('1:01');
    expect(formatDuration(3_725_000)).toBe('1:02:05');
    expect(formatDuration(-5)).toBe('0:00');
    expect(formatDuration(Number.NaN)).toBe('0:00');
  });
});

describe('transcriptPreview', () => {
  it('joins lines and truncates', () => {
    expect(transcriptPreview('one\ntwo\n\nthree')).toBe('one / two / three');
    expect(transcriptPreview('a'.repeat(100), 10)).toBe(`${'a'.repeat(9)}…`);
    expect(transcriptPreview('')).toBe('');
  });
});

describe('formatRecordedAt', () => {
  it('formats ISO and python str(datetime) timestamps, and passes garbage through', () => {
    expect(formatRecordedAt('2026-10-13T16:49:57Z', 'en-GB')).toMatch(/2026/);
    expect(formatRecordedAt('2026-10-13 16:49:57.893996+00:00', 'en-GB')).toMatch(/2026/);
    expect(formatRecordedAt('not a date')).toBe('not a date');
  });
});
