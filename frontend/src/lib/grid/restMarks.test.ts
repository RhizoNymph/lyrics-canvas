import { describe, it, expect } from 'vitest';
import { REST_MARK, displayLine, displayText, hasWords, lineWords, tokenizeLine } from './restMarks';

describe('tokenizeLine', () => {
  it('numbers word tokens only and keeps punctuation on its word', () => {
    expect(tokenizeLine('hey_you, _ there')).toEqual([
      { kind: 'word', text: 'hey', wordIndex: 0 },
      { kind: 'rest' },
      { kind: 'word', text: 'you,', wordIndex: 1 },
      { kind: 'rest' },
      { kind: 'word', text: 'there', wordIndex: 2 },
    ]);
  });

  it('gives nothing for blank text', () => {
    expect(tokenizeLine('   \t ')).toEqual([]);
  });

  it('uses _ as the rest mark', () => {
    expect(REST_MARK).toBe('_');
  });
});

describe('lineWords / displayLine / hasWords', () => {
  it('lists the words without rests', () => {
    expect(lineWords('  _I _ go__home ')).toEqual(['I', 'go', 'home']);
    expect(displayLine('_I _ go__home')).toBe('I go home');
    expect(lineWords('_ _')).toEqual([]);
  });

  it('hasWords is false for blank and rest-only text', () => {
    expect(hasWords('')).toBe(false);
    expect(hasWords('_ _\n_')).toBe(false);
    expect(hasWords('_\nyo')).toBe(true);
  });
});

describe('displayText', () => {
  it('keeps stanza breaks but drops rest-only lines', () => {
    expect(displayText('a\n\n_\nb _')).toBe('a\n\nb');
  });

  it('is empty for rest-only text', () => {
    expect(displayText('_\n_ _')).toBe('');
  });

  it('collapses whitespace inside a line', () => {
    expect(displayText('one\t two')).toBe('one two');
  });
});
