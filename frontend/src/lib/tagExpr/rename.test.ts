import { describe, it, expect } from 'vitest';
import { mentionsTag, renameInText } from './rename';
import { parse } from './parser';

describe('renameInText', () => {
  it('renames and normalizes text that mentions the tag', () => {
    expect(renameInText('Love   and night', 'love', 'romance')).toBe('romance & night');
  });

  it('leaves text that does not mention the tag untouched', () => {
    expect(renameInText('night   and dark', 'love', 'romance')).toBe('night   and dark');
  });

  it('leaves invalid text untouched', () => {
    expect(renameInText('love &', 'love', 'romance')).toBe('love &');
  });
});

describe('mentionsTag', () => {
  it('finds tags case-insensitively anywhere, but not in type terms', () => {
    const p = parse('a | !(b & LOVE) | type:verse');
    expect(p.ok && mentionsTag(p.expr, 'love')).toBe(true);
    const q = parse('type:verse');
    expect(q.ok && mentionsTag(q.expr, 'verse')).toBe(false);
    expect(mentionsTag(null, 'x')).toBe(false);
  });
});
