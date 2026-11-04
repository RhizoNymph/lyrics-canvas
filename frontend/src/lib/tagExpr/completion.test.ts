import { describe, it, expect } from 'vitest';
import { applySuggestion, completionContextAt, suggestionsFor } from './completion';
import type { CompletionTag, Suggestion } from './completion';
import { cp } from './text';

const TAGS: CompletionTag[] = [
  { name: 'love', color: '#f00' },
  { name: 'late night', color: '#0f0' },
  { name: 'glove', color: '#00f' },
  { name: 'draft', color: '#999' },
  { name: '🔥', color: '#f80' },
];

/** `‸` in `marked` is the caret. */
function at(marked: string) {
  const i = Array.from(marked).indexOf('‸');
  const text = Array.from(marked).filter((_, k) => k !== i).join('');
  return { text, caret: cp(i), ctx: completionContextAt(text, cp(i)) };
}

const labels = (s: Suggestion[]) => s.map((x) => x.label);

describe('completionContextAt', () => {
  it('replaces the whole word under the caret', () => {
    const { ctx } = at('lo‸ve & night');
    expect(ctx).toMatchObject({ replaceStart: 0, replaceEnd: 4, prefix: 'lo', quoted: false, slot: 'operand' });
  });

  it('is an operand slot after operators, keywords and parens', () => {
    for (const m of ['‸', 'a & ‸', 'a | ‸', '!‸', '(‸', 'a and ‸', 'a or ‸', 'not ‸', 'a AND ‸']) {
      expect(at(m).ctx.slot).toBe('operand');
    }
  });

  it('accepts an operator after a complete operand', () => {
    for (const m of ['love ‸', 'love  ‸', '(a) ‸', '"late night" ‸', 'type:verse ‸', 'andy ‸']) {
      expect(at(m).ctx.slot).toBe('operand_or_operator');
    }
  });

  it('detects a caret inside a quoted name and unescapes the prefix', () => {
    const { ctx } = at('a & "late \\"n‸');
    expect(ctx).toMatchObject({ replaceStart: 4, quoted: true, prefix: 'late "n' });
  });

  it('replaces through the closing quote', () => {
    const { ctx } = at('"la‸te" & x');
    expect(ctx).toMatchObject({ replaceStart: 0, replaceEnd: 6, prefix: 'la', quoted: true });
  });

  it('counts positions in code points', () => {
    const { ctx } = at('🔥 & lo‸');
    expect(ctx).toMatchObject({ replaceStart: 4, replaceEnd: 6, prefix: 'lo' });
  });
});

describe('suggestionsFor', () => {
  it('ranks prefix matches before substring matches, case-insensitively', () => {
    expect(labels(suggestionsFor(at('LO‸').ctx, TAGS))).toEqual(['love', 'glove']);
  });

  it('offers type values after type:', () => {
    expect(labels(suggestionsFor(at('type:‸').ctx, TAGS))).toEqual([
      'type:verse', 'type:chorus', 'type:bridge', 'type:hook', 'type:freestyle',
    ]);
    expect(labels(suggestionsFor(at('a & TYPE:ch‸').ctx, TAGS))).toEqual(['type:chorus']);
  });

  it('offers type terms for a prefix of "type:" or of a type value', () => {
    expect(labels(suggestionsFor(at('ty‸').ctx, TAGS))).toContain('type:verse');
    expect(labels(suggestionsFor(at('cho‸').ctx, TAGS))).toEqual(['type:chorus']);
  });

  it('offers and/or only where an operator fits', () => {
    expect(labels(suggestionsFor(at('love ‸').ctx, TAGS)).slice(0, 2)).toEqual(['and', 'or']);
    expect(labels(suggestionsFor(at('love o‸').ctx, TAGS))).toEqual(['or', 'love', 'glove']);
    expect(labels(suggestionsFor(at('love & o‸').ctx, TAGS))).not.toContain('or');
    expect(labels(suggestionsFor(at('‸').ctx, TAGS))).not.toContain('and');
  });

  it('offers not for a matching prefix in either slot', () => {
    expect(labels(suggestionsFor(at('no‸').ctx, TAGS))).toContain('not');
    expect(labels(suggestionsFor(at('a no‸').ctx, TAGS))).toContain('not');
  });

  it('inside quotes offers tag names only', () => {
    expect(labels(suggestionsFor(at('"la‸').ctx, TAGS))).toEqual(['late night']);
  });

  it('quotes names that are not bare words', () => {
    const [first] = suggestionsFor(at('lat‸').ctx, TAGS);
    expect(first).toMatchObject({ kind: 'tag', label: 'late night', insertText: '"late night"', color: '#0f0' });
  });

  it('honours the limit', () => {
    expect(suggestionsFor(at('‸').ctx, TAGS, 3)).toHaveLength(3);
  });
});

describe('applySuggestion', () => {
  const tag = (name: string, insertText = name): Suggestion => ({ kind: 'tag', label: name, insertText, color: '#000' });

  it('replaces the word and leaves the caret after a space', () => {
    const { text, ctx } = at('lo‸');
    expect(applySuggestion(text, ctx, tag('love'))).toEqual({ text: 'love ', caret: 5 });
  });

  it('keeps the rest of the text and steps over an existing space', () => {
    const { text, ctx } = at('lo‸ & night');
    expect(applySuggestion(text, ctx, tag('love'))).toEqual({ text: 'love & night', caret: 5 });
  });

  it('separates from a preceding operator or operand', () => {
    const { text, ctx } = at('a &‸');
    expect(applySuggestion(text, ctx, tag('love')).text).toBe('a & love ');
  });

  it('does not separate after ( or ! and stays tight before )', () => {
    const r = at('(!lo‸)');
    expect(applySuggestion(r.text, r.ctx, tag('love'))).toEqual({ text: '(!love)', caret: 6 });
  });

  it('replaces a whole quoted name', () => {
    const { text, ctx } = at('"la‸te" & x');
    expect(applySuggestion(text, ctx, tag('late night', '"late night"')).text).toBe('"late night" & x');
  });

  it('places the caret in code points', () => {
    const { text, ctx } = at('🔥 & lo‸');
    expect(applySuggestion(text, ctx, tag('love'))).toEqual({ text: '🔥 & love ', caret: 9 });
  });
});
