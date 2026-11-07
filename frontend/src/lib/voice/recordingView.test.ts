import { describe, it, expect } from 'vitest';
import { correctionPrefill, referenceOf, verificationOf, withVerification } from './recordingView';
import type { RecordingResponse, TimedWord } from './types';

const base: RecordingResponse = {
  id: 'r1',
  snippet_id: 's1',
  mime_type: 'audio/webm',
  duration_ms: 4000,
  byte_size: 10,
  transcript: 'hello world\n\nagain',
  split_mode: 'taps',
  created_at: '2026-11-01T10:00:00Z',
};

const w = (text: string, start_ms: number, end_ms: number): TimedWord => ({ text, start_ms, end_ms, probability: 0.9 });
const words = [w('hello', 0, 400), w('world', 450, 900), w('again', 1500, 1900)];

describe('verificationOf', () => {
  it('is unchecked for a pre-migration recording without the fields', () => {
    expect(verificationOf(base)).toEqual({ kind: 'unchecked' });
  });

  it('is unchecked when both are null', () => {
    expect(verificationOf({ ...base, verified_text: null, verified_at: null })).toEqual({ kind: 'unchecked' });
  });

  it('is checked with text and date when both are set', () => {
    expect(verificationOf({ ...base, verified_text: 'hi', verified_at: '2026-11-02T00:00:00Z' })).toEqual({
      kind: 'checked',
      text: 'hi',
      at: '2026-11-02T00:00:00Z',
    });
  });

  it('treats a text without a date (contract violation) as checked, never loses the text', () => {
    expect(verificationOf({ ...base, verified_text: 'hi', verified_at: null })).toEqual({ kind: 'checked', text: 'hi', at: null });
  });

  it('treats a date without text as unchecked', () => {
    expect(verificationOf({ ...base, verified_text: null, verified_at: '2026-11-02T00:00:00Z' })).toEqual({ kind: 'unchecked' });
  });
});

describe('correctionPrefill', () => {
  it('uses the verified text when checked', () => {
    expect(correctionPrefill({ ...base, verified_text: 'fixed\ntext', verified_at: 'x' })).toBe('fixed\ntext');
  });

  it('uses lines joined by newlines, else the transcript', () => {
    expect(correctionPrefill({ ...base, lines: ['a', 'b'] })).toBe('a\nb');
    expect(correctionPrefill(base)).toBe('hello world\n\nagain');
  });
});

describe('referenceOf', () => {
  it('is untimed lines from the transcript without timings', () => {
    expect(referenceOf(base)).toEqual({ kind: 'untimed', lines: ['hello world', '', 'again'] });
    expect(referenceOf({ ...base, has_timings: false, words: null, line_spans: null, lines: ['x'] })).toEqual({
      kind: 'untimed',
      lines: ['x'],
    });
  });

  it('groups words into lines by span, blank lines included', () => {
    const ref = referenceOf({
      ...base,
      has_timings: true,
      words,
      line_spans: [
        [0, 2],
        [2, 2],
        [2, 3],
      ],
    });
    expect(ref.kind).toBe('timed');
    if (ref.kind !== 'timed') return;
    expect(ref.words).toBe(words);
    expect(ref.lines).toEqual([
      { text: 'hello world', words: [0, 1] },
      { text: '', words: [] },
      { text: 'again', words: [2] },
    ]);
  });

  it('falls back to untimed when the spans are inconsistent with the words', () => {
    const timed = { ...base, has_timings: true, words };
    // gap
    expect(referenceOf({ ...timed, line_spans: [[0, 1], [2, 3]] }).kind).toBe('untimed');
    // overlap
    expect(referenceOf({ ...timed, line_spans: [[0, 2], [1, 3]] }).kind).toBe('untimed');
    // does not cover every word
    expect(referenceOf({ ...timed, line_spans: [[0, 2]] }).kind).toBe('untimed');
    // past the end
    expect(referenceOf({ ...timed, line_spans: [[0, 4]] }).kind).toBe('untimed');
    // reversed
    expect(referenceOf({ ...timed, line_spans: [[0, 2], [3, 2]] }).kind).toBe('untimed');
    // has_timings but missing arrays
    expect(referenceOf({ ...base, has_timings: true, words: null, line_spans: null }).kind).toBe('untimed');
  });

  it('builds line text from the words when the lines field is absent', () => {
    const ref = referenceOf({ ...base, transcript: '', has_timings: true, words, line_spans: [[0, 3]] });
    expect(ref.kind === 'timed' && ref.lines[0].text).toBe('hello world again');
  });
});

describe('withVerification', () => {
  it('replaces the row with the server response, keeping its other fields', () => {
    const updated = { ...base, verified_text: 'x', verified_at: 'now' };
    expect(withVerification([base, { ...base, id: 'r2' }], updated)).toEqual([updated, { ...base, id: 'r2' }]);
  });
});
