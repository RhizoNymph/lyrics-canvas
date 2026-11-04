import { describe, it, expect } from 'vitest';
import { insertTagAtCaret } from './insert';
import { cp } from './text';

const ins = (text: string, caret: number, name: string) => insertTagAtCaret(text, cp(caret), name);

describe('insertTagAtCaret', () => {
  it('inserts into an empty expression', () => {
    expect(ins('', 0, 'love')).toEqual({ text: 'love', caret: 4 });
  });

  it('joins with & after an operand', () => {
    expect(ins('night', 5, 'love')).toEqual({ text: 'night & love', caret: 12 });
    expect(ins('night  ', 7, 'love').text).toBe('night & love');
    expect(ins('(a | b)', 7, 'love').text).toBe('(a | b) & love');
  });

  it('does not add & after an operator or keyword', () => {
    expect(ins('night &', 7, 'love').text).toBe('night & love');
    expect(ins('night or ', 9, 'love').text).toBe('night or love');
    expect(ins('(', 1, 'love').text).toBe('(love');
    expect(ins('!', 1, 'love').text).toBe('!love');
  });

  it('joins with & before a following operand', () => {
    expect(ins('a | b', 0, 'love')).toEqual({ text: 'love & a | b', caret: 4 });
    expect(ins('night & ', 0, 'x').text).toBe('x & night & ');
  });

  it('stays tight before a closing paren', () => {
    expect(ins('(a | )', 5, 'love').text).toBe('(a | love)');
  });

  it('moves out of a word or quoted name before inserting', () => {
    expect(ins('ni', 1, 'love').text).toBe('ni & love');
    expect(ins('"late night" | b', 3, 'love').text).toBe('"late night" & love | b');
  });

  it('quotes names that need it', () => {
    expect(ins('a', 1, 'late night').text).toBe('a & "late night"');
    expect(ins('', 0, 'or').text).toBe('"or"');
  });

  it('counts the caret in code points', () => {
    expect(ins('🔥', 1, 'love')).toEqual({ text: '🔥 & love', caret: 8 });
  });
});
