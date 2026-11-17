import { describe, it, expect } from 'vitest';
import {
  DEFAULT_EXPIRY, EXPIRY_CHOICES, MAX_TOKEN_NAME_LENGTH, addToken, checkTokenName, expiryFromValue, expiryValue,
  formatTokenRow, removeToken, sortTokens,
} from './tokens';
import type { ApiToken } from '@/types/apiTokens';

const tok = (id: string, created_at: string, extra: Partial<ApiToken> = {}): ApiToken => ({
  id, name: `n-${id}`, prefix: 'lct_abcdefgh', created_at, last_used_at: null, expires_at: null, ...extra,
});

describe('checkTokenName', () => {
  it('trims and accepts 1..64 code points', () => {
    expect(checkTokenName('  laptop agent ')).toEqual({ ok: true, name: 'laptop agent' });
    expect(checkTokenName('x'.repeat(MAX_TOKEN_NAME_LENGTH))).toMatchObject({ ok: true });
    expect(checkTokenName('🎤'.repeat(64))).toMatchObject({ ok: true });
  });

  it('refuses blank and too long names with invalid_token_name', () => {
    for (const raw of ['', '   ', 'x'.repeat(65)]) {
      const r = checkTokenName(raw);
      expect(r.ok).toBe(false);
      if (!r.ok) expect(r.error.kind).toBe('invalid_token_name');
    }
  });
});

describe('expiry choices', () => {
  it('offers 30/90/365/never with 90 as the default', () => {
    expect(EXPIRY_CHOICES.map((c) => c.days)).toEqual([30, 90, 365, null]);
    expect(EXPIRY_CHOICES.map((c) => c.label)).toEqual(['30 days', '90 days', '1 year', 'Never']);
    expect(DEFAULT_EXPIRY).toBe(90);
  });

  it('round-trips through select option values', () => {
    for (const c of EXPIRY_CHOICES) expect(expiryFromValue(expiryValue(c.days))).toBe(c.days);
    expect(expiryValue(null)).toBe('never');
    expect(expiryFromValue('bogus')).toBe(DEFAULT_EXPIRY);
  });
});

describe('formatTokenRow', () => {
  const now = new Date('2026-11-14T12:00:00Z');
  const fmt = (iso: string) => `D(${iso.slice(0, 10)})`;

  it('shows prefix with an ellipsis and never-used / never-expires', () => {
    expect(formatTokenRow(tok('a', '2026-11-01T00:00:00Z'), now, fmt)).toEqual({
      prefix: 'lct_abcdefgh…',
      created: 'D(2026-11-01)',
      lastUsed: 'Never used',
      expires: 'Never',
      expired: false,
    });
  });

  it('shows the dates and flags expired tokens', () => {
    const row = formatTokenRow(
      tok('a', '2026-08-01T00:00:00Z', { last_used_at: '2026-11-10T00:00:00Z', expires_at: '2026-11-13T00:00:00Z' }),
      now,
      fmt,
    );
    expect(row).toMatchObject({ lastUsed: 'D(2026-11-10)', expires: 'Expired D(2026-11-13)', expired: true });
    const live = formatTokenRow(tok('a', '2026-11-01T00:00:00Z', { expires_at: '2027-02-01T00:00:00Z' }), now, fmt);
    expect(live).toMatchObject({ expires: 'D(2027-02-01)', expired: false });
  });
});

describe('list helpers', () => {
  it('sorts newest first, id as tie-break, without mutating', () => {
    const input = [tok('b', '2026-11-01T00:00:00Z'), tok('a', '2026-11-03T00:00:00Z'), tok('c', '2026-11-01T00:00:00Z')];
    expect(sortTokens(input).map((t) => t.id)).toEqual(['a', 'b', 'c']);
    expect(input.map((t) => t.id)).toEqual(['b', 'a', 'c']);
  });

  it('adds a token on top (replacing the same id) and removes by id', () => {
    const list = [tok('a', '2026-11-01T00:00:00Z')];
    const added = addToken(list, tok('b', '2026-11-05T00:00:00Z'));
    expect(added.map((t) => t.id)).toEqual(['b', 'a']);
    expect(addToken(added, { ...tok('b', '2026-11-05T00:00:00Z'), name: 'renamed' }).filter((t) => t.id === 'b')).toHaveLength(1);
    expect(removeToken(added, 'b').map((t) => t.id)).toEqual(['a']);
    expect(removeToken(added, 'zzz')).toEqual(added);
  });
});
