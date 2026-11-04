import { describe, it, expect } from 'vitest';
import { errorMarker, markerFor } from './validation';
import { cp } from '@/lib/tagExpr';

describe('markerFor', () => {
  it('is null for valid text', () => {
    expect(markerFor('love & night')).toBeNull();
    expect(markerFor('')).toBeNull();
  });

  it('splits the text at the error code point', () => {
    const m = markerFor('love | | night');
    expect(m).toMatchObject({ before: 'love | ', at: '|', after: ' night', position: 7 });
    expect(m?.error.code).toBe('unexpected_token');
  });

  it('marks the end for unexpected_end', () => {
    expect(markerFor('love &')).toMatchObject({ before: 'love &', at: '', after: '', position: 6 });
  });

  it('counts emoji as single code points', () => {
    expect(markerFor('🔥🔥 & "x')).toMatchObject({ before: '🔥🔥 & ', at: '"', after: 'x', position: 5 });
  });

  it('elides long context on both sides', () => {
    const text = `${'a'.repeat(40)} ) ${'b'.repeat(40)}`;
    const m = markerFor(text);
    expect(m?.at).toBe(')');
    expect(m?.before.startsWith('…')).toBe(true);
    expect(m?.after.endsWith('…')).toBe(true);
  });

  it('clamps a position past the end', () => {
    expect(errorMarker('ab', { code: 'too_long', position: cp(500), message: 'm' })).toMatchObject({ before: 'ab', at: '' });
  });
});
