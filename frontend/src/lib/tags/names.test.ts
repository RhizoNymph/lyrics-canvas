import { describe, it, expect } from 'vitest';
import { byName, checkName, isHexColor, sameName } from './names';

describe('checkName', () => {
  it('trims and accepts 1-100 code points', () => {
    expect(checkName('  love ')).toEqual({ ok: true, name: 'love' });
    expect(checkName('🔥'.repeat(100))).toEqual({ ok: true, name: '🔥'.repeat(100) });
  });

  it('rejects empty, too long and control characters', () => {
    expect(checkName(' \t ')).toEqual({ ok: false, problem: 'empty' });
    expect(checkName('🔥'.repeat(101))).toEqual({ ok: false, problem: 'too_long' });
    expect(checkName('a\nb')).toEqual({ ok: false, problem: 'control_character' });
    expect(checkName('a\u0085b')).toEqual({ ok: false, problem: 'control_character' });
  });
});

describe('isHexColor', () => {
  it('accepts #rrggbb only', () => {
    expect(isHexColor('#6366f1')).toBe(true);
    expect(isHexColor('#ABCDEF')).toBe(true);
    expect(isHexColor('#fff')).toBe(false);
    expect(isHexColor('6366f1')).toBe(false);
    expect(isHexColor('#6366fg')).toBe(false);
  });
});

describe('byName / sameName', () => {
  it('orders and compares case-insensitively', () => {
    expect([{ name: 'b' }, { name: 'A' }, { name: 'a2' }].sort(byName).map((x) => x.name)).toEqual(['A', 'a2', 'b']);
    expect(sameName(' Love', 'love ')).toBe(true);
  });
});
