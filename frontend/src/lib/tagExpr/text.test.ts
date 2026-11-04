import { describe, it, expect } from 'vitest';
import { cp, cpLength, cpToUtf16, u16, utf16ToCp } from './text';

describe('code point offsets', () => {
  it('counts emoji as one code point', () => {
    expect(cpLength('🔥 & a')).toBe(5);
    expect('🔥 & a'.length).toBe(6);
  });

  it('converts both ways around surrogate pairs', () => {
    const s = '🔥🔥 & "x';
    expect(cpToUtf16(s, cp(5))).toBe(7);
    expect(utf16ToCp(s, u16(7))).toBe(5);
    expect(utf16ToCp(s, u16(1))).toBe(1); // inside the first pair rounds up past it
    expect(cpToUtf16(s, cp(99))).toBe(s.length);
  });
});
