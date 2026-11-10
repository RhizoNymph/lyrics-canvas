import { describe, it, expect } from 'vitest';
import { MAX_VERIFICATION_CODE_POINTS, checkVerificationText, normalizeVerificationText } from './verification';

describe('normalizeVerificationText (mirrors the server)', () => {
  it('turns CRLF and CR into LF', () => {
    expect(normalizeVerificationText('a\r\nb\rc')).toBe('a\nb\nc');
  });

  it('strips trailing whitespace on every line, keeps leading', () => {
    expect(normalizeVerificationText('  a  \nb\t')).toBe('  a\nb');
  });

  it('drops blank lines at the start and end', () => {
    expect(normalizeVerificationText('\n \n a\nb\n\n  \n')).toBe(' a\nb');
  });

  it('collapses runs of blank lines into one', () => {
    expect(normalizeVerificationText('a\n\n\n \nb\n\nc')).toBe('a\n\nb\n\nc');
  });
});

describe('checkVerificationText', () => {
  it('accepts non-empty text and returns it normalized', () => {
    expect(checkVerificationText('hello \r\nworld')).toEqual({ ok: true, text: 'hello\nworld' });
  });

  it('rejects text that is empty after normalizing', () => {
    expect(checkVerificationText(' \n\t\n')).toEqual({ ok: false, reason: 'empty' });
  });

  it('counts code points, not UTF-16 units', () => {
    expect(MAX_VERIFICATION_CODE_POINTS).toBe(10_000);
    const emoji = '😀'.repeat(10_000); // 20 000 UTF-16 units
    expect(checkVerificationText(emoji).ok).toBe(true);
    expect(checkVerificationText(`${emoji}a`)).toEqual({ ok: false, reason: 'too_long' });
  });
});
