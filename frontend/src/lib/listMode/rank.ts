/**
 * Custom list order keys: an exact port of `fractional-indexing` 3.2.0 (rocicorp) `generateKeyBetween` /
 * `generateNKeysBetween` with the default base-62 digits, so web, Android and the backend generate the same
 * keys (shared vectors: spec/list_rank_vectors.json). Keys compare by plain code unit order (ASCII), never
 * by locale. Every failure is a `RankError`. One addition to 3.2.0, shared with the backend: a key (or bound)
 * with a character outside 0-9A-Za-z is invalid.
 */

export const BASE_62_DIGITS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';

/** The server column is varchar(64); longer keys are rejected (422 invalid_list_rank). */
export const MAX_RANK_LENGTH = 64;

/** The one key `validateOrderKey` rejects although it is well formed (nothing can go before it). */
const SMALLEST_INTEGER = 'A' + BASE_62_DIGITS[0].repeat(26);

export type RankErrorKind =
  /** A bound is not a valid order key. */
  | 'invalid_key'
  /** `a >= b`. */
  | 'out_of_order'
  /** The integer part cannot grow or shrink any further. */
  | 'exhausted';

export class RankError extends Error {
  readonly kind: RankErrorKind;

  constructor(kind: RankErrorKind, message: string) {
    super(message);
    this.name = 'RankError';
    this.kind = kind;
  }
}

export type RankInvalidReason =
  | 'invalid_head'
  | 'too_short'
  | 'smallest_key'
  | 'trailing_zero'
  /** A character outside 0-9A-Za-z (3.2.0 does not check this; the backend and the vectors do). */
  | 'invalid_digit'
  | 'too_long';

export type RankCheck = { ok: true; rank: string } | { ok: false; reason: RankInvalidReason };

/** Plain code unit order (the server's byte order for ASCII keys). */
export function compareRanks(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

function integerLength(head: string | undefined): number | null {
  if (head === undefined) return null;
  if (head >= 'a' && head <= 'z') return head.charCodeAt(0) - 'a'.charCodeAt(0) + 2;
  if (head >= 'A' && head <= 'Z') return 'Z'.charCodeAt(0) - head.charCodeAt(0) + 2;
  return null;
}

/**
 * What `validateOrderKey` accepts, as a typed result, restricted to base-62 characters (as the backend does:
 * other characters would make `indexOf` return -1 later), plus the 64-character limit.
 */
export function validateRank(key: string): RankCheck {
  const check = checkOrderKey(key);
  if (!check.ok) return check;
  if (key.length > MAX_RANK_LENGTH) return { ok: false, reason: 'too_long' };
  return check;
}

function checkOrderKey(key: string): RankCheck {
  if (key === SMALLEST_INTEGER) return { ok: false, reason: 'smallest_key' };
  const length = integerLength(key[0]);
  if (length === null) return { ok: false, reason: 'invalid_head' };
  if (length > key.length) return { ok: false, reason: 'too_short' };
  if (key.length > length && key.endsWith(BASE_62_DIGITS[0])) return { ok: false, reason: 'trailing_zero' };
  for (const ch of key) if (!BASE_62_DIGITS.includes(ch)) return { ok: false, reason: 'invalid_digit' };
  return { ok: true, rank: key };
}

function requireOrderKey(key: string): void {
  const check = checkOrderKey(key);
  if (!check.ok) throw new RankError('invalid_key', `invalid order key: ${key} (${check.reason})`);
}

function integerPart(key: string): string {
  const length = integerLength(key[0]);
  if (length === null) throw new RankError('invalid_key', `invalid order key head: ${key[0]}`);
  if (length > key.length) throw new RankError('invalid_key', `invalid order key: ${key}`);
  return key.slice(0, length);
}

function validateInteger(int: string): void {
  if (int.length !== integerLength(int[0])) {
    throw new RankError('invalid_key', `invalid integer part of order key: ${int}`);
  }
}

/** A key strictly between fractional parts `a` and `b` (`b` null = no upper bound). */
function midpoint(a: string, b: string | null, digits: string): string {
  const zero = digits[0];
  if (b !== null && a >= b) throw new RankError('out_of_order', `${a} >= ${b}`);
  if (a.slice(-1) === zero || (b !== null && b.slice(-1) === zero)) {
    throw new RankError('invalid_key', 'trailing zero');
  }
  if (b) {
    // Remove the longest common prefix, padding `a` with zeros; `b` cannot end inside the prefix.
    let n = 0;
    while ((a[n] || zero) === b[n]) n++;
    if (n > 0) return b.slice(0, n) + midpoint(a.slice(n), b.slice(n), digits);
  }
  // The first digits (or the lack of one) differ.
  const digitA = a ? digits.indexOf(a[0]) : 0;
  const digitB = b !== null ? digits.indexOf(b[0]) : digits.length;
  if (digitB - digitA > 1) return digits[Math.round(0.5 * (digitA + digitB))];
  // Consecutive first digits.
  if (b && b.length > 1) return b.slice(0, 1);
  return digits[digitA] + midpoint(a.slice(1), null, digits);
}

function incrementInteger(x: string, digits: string): string | null {
  validateInteger(x);
  const [head, ...digs] = x.split('');
  let carry = true;
  for (let i = digs.length - 1; carry && i >= 0; i--) {
    const d = digits.indexOf(digs[i]) + 1;
    if (d === digits.length) {
      digs[i] = digits[0];
    } else {
      digs[i] = digits[d];
      carry = false;
    }
  }
  if (!carry) return head + digs.join('');
  if (head === 'Z') return 'a' + digits[0];
  if (head === 'z') return null;
  const h = String.fromCharCode(head.charCodeAt(0) + 1);
  if (h > 'a') digs.push(digits[0]);
  else digs.pop();
  return h + digs.join('');
}

function decrementInteger(x: string, digits: string): string | null {
  validateInteger(x);
  const [head, ...digs] = x.split('');
  let borrow = true;
  for (let i = digs.length - 1; borrow && i >= 0; i--) {
    const d = digits.indexOf(digs[i]) - 1;
    if (d === -1) {
      digs[i] = digits.slice(-1);
    } else {
      digs[i] = digits[d];
      borrow = false;
    }
  }
  if (!borrow) return head + digs.join('');
  if (head === 'a') return 'Z' + digits.slice(-1);
  if (head === 'A') return null;
  const h = String.fromCharCode(head.charCodeAt(0) - 1);
  if (h < 'Z') digs.push(digits.slice(-1));
  else digs.pop();
  return h + digs.join('');
}

/**
 * `generateKeyBetween(a, b)`: a key strictly between `a` and `b`; null means no bound on that side.
 * Throws `RankError` on an invalid bound or `a >= b`.
 */
export function keyBetween(a: string | null, b: string | null): string {
  const digits = BASE_62_DIGITS;
  if (a !== null) requireOrderKey(a);
  if (b !== null) requireOrderKey(b);
  if (a !== null && b !== null && a >= b) throw new RankError('out_of_order', `${a} >= ${b}`);

  if (a === null) {
    if (b === null) return 'a' + digits[0];
    const ib = integerPart(b);
    const fb = b.slice(ib.length);
    if (ib === SMALLEST_INTEGER) return ib + midpoint('', fb, digits);
    if (ib < b) return ib;
    const res = decrementInteger(ib, digits);
    if (res === null) throw new RankError('exhausted', 'cannot decrement any more');
    return res;
  }

  if (b === null) {
    const ia = integerPart(a);
    const fa = a.slice(ia.length);
    const i = incrementInteger(ia, digits);
    return i === null ? ia + midpoint(fa, null, digits) : i;
  }

  const ia = integerPart(a);
  const fa = a.slice(ia.length);
  const ib = integerPart(b);
  const fb = b.slice(ib.length);
  if (ia === ib) return ia + midpoint(fa, fb, digits);
  const i = incrementInteger(ia, digits);
  if (i === null) throw new RankError('exhausted', 'cannot increment any more');
  if (i < b) return i;
  return ia + midpoint(fa, null, digits);
}

/** `generateNKeysBetween(a, b, n)`: `n` increasing keys strictly between `a` and `b`. */
export function nKeysBetween(a: string | null, b: string | null, n: number): string[] {
  if (n === 0) return [];
  if (n === 1) return [keyBetween(a, b)];
  if (b === null) {
    let c = keyBetween(a, b);
    const result = [c];
    for (let i = 0; i < n - 1; i++) {
      c = keyBetween(c, b);
      result.push(c);
    }
    return result;
  }
  if (a === null) {
    let c = keyBetween(a, b);
    const result = [c];
    for (let i = 0; i < n - 1; i++) {
      c = keyBetween(a, c);
      result.push(c);
    }
    result.reverse();
    return result;
  }
  const mid = Math.floor(n / 2);
  const c = keyBetween(a, b);
  return [...nKeysBetween(a, c, mid), c, ...nKeysBetween(c, b, n - mid - 1)];
}
