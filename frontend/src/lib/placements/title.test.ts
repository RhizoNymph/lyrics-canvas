import { describe, it, expect } from 'vitest';
import { CANVAS_TITLE_MAX, DEFAULT_CANVAS_TITLE, checkCanvasTitle, checkNewCanvasTitle, titleEditKey, titleProblemMessage } from './title';

describe('checkCanvasTitle', () => {
  it('trims and accepts 1..255 characters', () => {
    expect(checkCanvasTitle('  Night drive  ')).toEqual({ ok: true, title: 'Night drive' });
    expect(checkCanvasTitle('a')).toEqual({ ok: true, title: 'a' });
    expect(checkCanvasTitle('x'.repeat(CANVAS_TITLE_MAX))).toEqual({ ok: true, title: 'x'.repeat(255) });
  });

  it('rejects an empty or blank title', () => {
    expect(checkCanvasTitle('')).toEqual({ ok: false, problem: 'empty' });
    expect(checkCanvasTitle(' \t\n ')).toEqual({ ok: false, problem: 'empty' });
  });

  it('rejects more than 255 characters after trimming', () => {
    expect(checkCanvasTitle('x'.repeat(256))).toEqual({ ok: false, problem: 'too_long' });
    expect(checkCanvasTitle(`  ${'x'.repeat(255)}  `).ok).toBe(true);
  });

  it('counts code points, not UTF-16 units', () => {
    // 255 astral characters are 510 UTF-16 units but 255 code points.
    expect(checkCanvasTitle('😀'.repeat(255)).ok).toBe(true);
    expect(checkCanvasTitle('😀'.repeat(256))).toEqual({ ok: false, problem: 'too_long' });
  });
});

describe('checkNewCanvasTitle', () => {
  it('falls back to the default title when blank, like the server', () => {
    expect(checkNewCanvasTitle('   ')).toEqual({ ok: true, title: DEFAULT_CANVAS_TITLE });
    expect(DEFAULT_CANVAS_TITLE).toBe('Untitled Canvas');
  });

  it('otherwise validates like a rename', () => {
    expect(checkNewCanvasTitle(' Hooks ')).toEqual({ ok: true, title: 'Hooks' });
    expect(checkNewCanvasTitle('x'.repeat(256))).toEqual({ ok: false, problem: 'too_long' });
  });
});

describe('titleProblemMessage', () => {
  it('describes each problem', () => {
    expect(titleProblemMessage('empty')).toBe('A canvas title cannot be empty');
    expect(titleProblemMessage('too_long')).toBe('Canvas titles are at most 255 characters');
  });
});

describe('titleEditKey', () => {
  it('Enter saves, Escape cancels, anything else is ignored', () => {
    expect(titleEditKey('Enter')).toBe('save');
    expect(titleEditKey('Escape')).toBe('cancel');
    expect(titleEditKey('a')).toBeNull();
    expect(titleEditKey('Tab')).toBeNull();
  });
});
