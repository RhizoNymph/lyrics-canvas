/** JSON value helpers for reading raw scores and grids (spec/score_format.md section 2, Integers). */

export type JsonObject = { readonly [key: string]: unknown };

export const MAX_SAFE_INT = Number.MAX_SAFE_INTEGER;

export function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** The integer a JSON value denotes (`12.0` is 12), or null (booleans, strings, fractions, |v| > 2^53-1). */
export function asInteger(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isInteger(value) || Math.abs(value) > MAX_SAFE_INT) return null;
  return value;
}

/** A finite JSON number (not a boolean), or null. */
export function asNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** True when the object's own keys are exactly `keys` (none missing, none extra). */
export function hasExactKeys(value: JsonObject, keys: readonly string[]): boolean {
  const own = Object.keys(value);
  return own.length === keys.length && keys.every((key) => Object.prototype.hasOwnProperty.call(value, key));
}

/** Structural JSON equality: numbers numerically, objects ignoring key order, arrays in order. */
export function jsonEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (typeof a !== typeof b || a === null || b === null || typeof a !== 'object') return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((item, i) => jsonEqual(item, b[i]));
  }
  const ao = a as JsonObject;
  const bo = b as JsonObject;
  const keys = Object.keys(ao);
  if (keys.length !== Object.keys(bo).length) return false;
  return keys.every((key) => Object.prototype.hasOwnProperty.call(bo, key) && jsonEqual(ao[key], bo[key]));
}
