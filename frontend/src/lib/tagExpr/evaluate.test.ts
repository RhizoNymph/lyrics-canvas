import { describe, it, expect } from 'vitest';
import { evaluate, parse } from './index';
import type { EvalSubject } from './index';

function run(text: string, subject: EvalSubject): boolean {
  const parsed = parse(text);
  if (!parsed.ok) throw new Error(parsed.error.code);
  return evaluate(parsed.expr, subject);
}

describe('evaluate on a typeless subject (word bank word)', () => {
  const word: EvalSubject = { type: null, tagNames: ['Love', 'night'] };

  it('makes every type: term false', () => {
    for (const t of ['verse', 'chorus', 'bridge', 'hook', 'freestyle']) expect(run(`type:${t}`, word)).toBe(false);
  });

  it('makes negated type: terms true', () => {
    expect(run('!type:verse', word)).toBe(true);
    expect(run('love & !type:chorus', word)).toBe(true);
  });

  it('still matches tags case-insensitively', () => {
    expect(run('love & NIGHT', word)).toBe(true);
    expect(run('love & draft', word)).toBe(false);
    expect(run('type:verse | night', word)).toBe(true);
  });

  it('matches the empty expression', () => {
    expect(run('', { type: null, tagNames: [] })).toBe(true);
  });
});
