import { describe, it, expect } from 'vitest';
import { checkBatch, checkWordText, describeWordProblem, normalizeWordText, splitAddInput, MAX_WORD_LENGTH } from './text';

describe('normalizeWordText', () => {
  it('strips the ends', () => {
    expect(normalizeWordText('  night  ')).toBe('night');
    expect(normalizeWordText('\t\nnight\r\n')).toBe('night');
  });

  it('collapses every internal whitespace run to one space', () => {
    expect(normalizeWordText('neon   lights')).toBe('neon lights');
    expect(normalizeWordText('neon\t\n lights')).toBe('neon lights');
    expect(normalizeWordText('a b')).toBe('a b');
    expect(normalizeWordText('a　 b')).toBe('a b');
  });

  it('applies Unicode NFC', () => {
    const decomposed = 'café';
    expect(normalizeWordText(decomposed)).toBe('café');
    expect(normalizeWordText(decomposed).length).toBe(4);
  });

  it('keeps the case', () => {
    expect(normalizeWordText('Night')).toBe('Night');
  });

  it('does not treat U+FEFF as whitespace (Python isspace)', () => {
    expect(normalizeWordText('﻿a')).toBe('﻿a');
  });
});

describe('checkWordText', () => {
  const ok = (raw: string, text = raw) => expect(checkWordText(raw)).toEqual({ ok: true, text });
  const bad = (raw: string, problem: string) => {
    const r = checkWordText(raw);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.problem.kind).toBe(problem);
  };

  it('accepts plain words and phrases', () => {
    ok('night');
    ok('Night');
    ok('neon lights');
    ok('one two three four');
  });

  it('accepts apostrophes (both kinds) and hyphens', () => {
    ok("don't");
    ok('don’t');
    ok('well-known');
    ok("rock 'n' roll");
    ok("'til");
    ok('-ing');
  });

  it('accepts letters from any script', () => {
    ok('café');
    ok('Straße');
    ok('ночь');
    ok('夜');
    ok('שלום');
    ok('ǅ');
  });

  it('returns the normalized text', () => {
    ok('  neon \t lights ', 'neon lights');
    ok('café', 'café');
  });

  it('rejects empty and whitespace-only text', () => {
    bad('', 'empty');
    bad('   ', 'empty');
    bad('\n\t', 'empty');
  });

  it('rejects more than 64 code points', () => {
    ok('a'.repeat(MAX_WORD_LENGTH));
    bad('a'.repeat(MAX_WORD_LENGTH + 1), 'too_long');
  });

  it('counts code points, not UTF-16 units', () => {
    // U+10400 (Deseret capital long I) is a letter outside the BMP: 2 UTF-16 units.
    const astral = '\u{10400}';
    ok(astral.repeat(MAX_WORD_LENGTH));
    bad(astral.repeat(MAX_WORD_LENGTH + 1), 'too_long');
  });

  it('measures the length after normalizing', () => {
    ok(`  ${'a'.repeat(64)}  `, 'a'.repeat(64));
    ok(`${'a'.repeat(30)}      ${'b'.repeat(33)}`, `${'a'.repeat(30)} ${'b'.repeat(33)}`);
  });

  it('allows at most 4 space-separated words', () => {
    ok('a b c d');
    bad('a b c d e', 'too_many_words');
    ok('a-b-c-d-e');
  });

  it('rejects digits, punctuation, symbols and emoji', () => {
    bad('night2', 'invalid_character');
    bad('hello!', 'invalid_character');
    bad('a,b', 'invalid_character');
    bad('a.b', 'invalid_character');
    bad('a_b', 'invalid_character');
    bad('love❤', 'invalid_character');
    bad('a–b', 'invalid_character');
    bad('a"b', 'invalid_character');
  });

  it('rejects combining marks that NFC cannot compose', () => {
    bad('á́', 'invalid_character');
  });

  it('reports the first bad character', () => {
    const r = checkWordText('ab1c!');
    expect(r).toEqual({ ok: false, problem: { kind: 'invalid_character', char: '1' } });
  });

  it('needs at least one letter', () => {
    bad("'", 'no_letter');
    bad('- -', 'no_letter');
    bad("'-'", 'no_letter');
  });

  it('checks length before word count before characters', () => {
    bad('1'.repeat(65), 'too_long');
    bad('1 2 3 4 5', 'too_many_words');
  });
});

describe('describeWordProblem', () => {
  it('has a message for every problem', () => {
    expect(describeWordProblem({ kind: 'empty' })).toMatch(/word/i);
    expect(describeWordProblem({ kind: 'too_long' })).toContain('64');
    expect(describeWordProblem({ kind: 'too_many_words' })).toContain('4');
    expect(describeWordProblem({ kind: 'invalid_character', char: '7' })).toContain('"7"');
    expect(describeWordProblem({ kind: 'no_letter' })).toMatch(/letter/i);
  });
});

describe('splitAddInput', () => {
  it('splits on commas and newlines, not spaces', () => {
    expect(splitAddInput('night, neon lights\nmoon')).toEqual(['night', 'neon lights', 'moon']);
  });

  it('handles CRLF and drops empty pieces', () => {
    expect(splitAddInput('a,,b\r\n\r\nc,  ,')).toEqual(['a', 'b', 'c']);
    expect(splitAddInput('')).toEqual([]);
    expect(splitAddInput(' , \n ')).toEqual([]);
  });

  it('trims each piece but keeps internal spacing for the validator', () => {
    expect(splitAddInput('  neon   lights  ')).toEqual(['neon   lights']);
  });

  it('keeps duplicates (the server collapses them)', () => {
    expect(splitAddInput('a, A, a')).toEqual(['a', 'A', 'a']);
  });
});

describe('checkBatch', () => {
  it('normalizes every word', () => {
    expect(checkBatch(['  night ', 'neon  lights'])).toEqual({ ok: true, words: ['night', 'neon lights'] });
  });

  it('reports the first bad index', () => {
    const r = checkBatch(['ok', 'bad1', 'also bad!']);
    expect(r).toEqual({ ok: false, reason: 'invalid_word', index: 1, problem: { kind: 'invalid_character', char: '1' } });
  });

  it('rejects an empty batch', () => {
    expect(checkBatch([])).toEqual({ ok: false, reason: 'empty' });
  });

  it('rejects more than 200 words', () => {
    expect(checkBatch(Array.from({ length: 200 }, () => 'a')).ok).toBe(true);
    expect(checkBatch(Array.from({ length: 201 }, () => 'a'))).toEqual({ ok: false, reason: 'too_many' });
  });
});
