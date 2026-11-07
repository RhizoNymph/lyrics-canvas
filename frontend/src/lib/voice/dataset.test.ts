import { describe, it, expect } from 'vitest';
import { fallbackExportFilename, filenameFromContentDisposition, formatAudioTotal, formatDatasetStats } from './dataset';

describe('filenameFromContentDisposition', () => {
  it('reads a quoted filename', () => {
    expect(
      filenameFromContentDisposition('attachment; filename="lyrics-canvas-voice-dataset-20261106T101500Z.zip"'),
    ).toBe('lyrics-canvas-voice-dataset-20261106T101500Z.zip');
  });

  it('reads an unquoted filename and ignores case and spacing', () => {
    expect(filenameFromContentDisposition('Attachment;FILENAME=data.zip')).toBe('data.zip');
  });

  it('prefers RFC 5987 filename* over filename', () => {
    expect(filenameFromContentDisposition(`attachment; filename="plain.zip"; filename*=UTF-8''voix%20d%C3%A9j%C3%A0.zip`)).toBe(
      'voix déjà.zip',
    );
  });

  it('is null without a usable name', () => {
    expect(filenameFromContentDisposition(null)).toBeNull();
    expect(filenameFromContentDisposition(undefined)).toBeNull();
    expect(filenameFromContentDisposition('attachment')).toBeNull();
    expect(filenameFromContentDisposition('attachment; filename=""')).toBeNull();
    expect(filenameFromContentDisposition(`attachment; filename*=UTF-8''%E0%A4%A`)).toBeNull();
  });

  it('strips any directory part', () => {
    expect(filenameFromContentDisposition('attachment; filename="../../etc/x.zip"')).toBe('x.zip');
    expect(filenameFromContentDisposition('attachment; filename="a\\\\b.zip"')).toBe('b.zip');
  });
});

describe('fallbackExportFilename', () => {
  it('follows the server pattern with a UTC stamp', () => {
    expect(fallbackExportFilename(new Date(Date.UTC(2026, 10, 6, 9, 5, 7, 123)))).toBe(
      'lyrics-canvas-voice-dataset-20261106T090507Z.zip',
    );
  });
});

describe('formatAudioTotal', () => {
  it('reads as minutes and seconds, hours when long', () => {
    expect(formatAudioTotal(0)).toBe('0s');
    expect(formatAudioTotal(42_400)).toBe('42s');
    expect(formatAudioTotal(250_000)).toBe('4m 10s');
    expect(formatAudioTotal(3_600_000)).toBe('1h 0m');
    expect(formatAudioTotal(3_725_000)).toBe('1h 2m');
    expect(formatAudioTotal(-1)).toBe('0s');
    expect(formatAudioTotal(Number.NaN)).toBe('0s');
  });
});

describe('formatDatasetStats', () => {
  it('matches the contract wording', () => {
    expect(
      formatDatasetStats({ total: 15, verified: 12, total_duration_ms: 400_000, verified_duration_ms: 250_000, with_timings: 9 }),
    ).toBe('12 of 15 takes checked · 4m 10s checked audio · 9 with word timings');
  });

  it('uses singular for one take', () => {
    expect(formatDatasetStats({ total: 1, verified: 0, total_duration_ms: 1000, verified_duration_ms: 0, with_timings: 1 })).toBe(
      '0 of 1 take checked · 0s checked audio · 1 with word timings',
    );
  });
});
