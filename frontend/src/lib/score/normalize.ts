/** Word and line keys and the LCS of reconcile (spec/score_format.md section 10). */
import { lineCells, wordGroups } from './gridView';
import type { LineJson } from './gridView';

const APOSTROPHE = "'";
const RIGHT_SINGLE_QUOTE = '’';
const FALLBACK_PREFIX = '#';
const KEPT = /^[\p{L}\p{Nd}']$/u;

/** NFC, per-code-point lowercase, `’` -> `'`, keep letters (L*), decimal digits (Nd) and `'`. */
export function normalizeWord(text: string): string {
  let lowered = '';
  for (const c of text.normalize('NFC')) lowered += c.toLowerCase();
  let out = '';
  for (const c of lowered) {
    const ch = c === RIGHT_SINGLE_QUOTE ? APOSTROPHE : c;
    if (KEPT.test(ch)) out += ch;
  }
  return out;
}

export function wordKey(spelling: string): string {
  const normalized = normalizeWord(spelling);
  return normalized !== '' ? normalized : FALLBACK_PREFIX + spelling.normalize('NFC');
}

export function wordKeys(line: LineJson): string[] {
  return wordGroups(lineCells(line)).map((group) => wordKey(group.spelling));
}

export function lineKey(line: LineJson): string {
  return wordKeys(line).join(' ');
}

/** A longest common subsequence as index pairs; `""` never matches; ties skip `a` first. */
export function lcsPairs(a: readonly string[], b: readonly string[]): [number, number][] {
  const n = a.length;
  const m = b.length;
  const eq = (i: number, j: number) => a[i] === b[j] && a[i] !== '';
  const width = m + 1;
  const table = new Int32Array((n + 1) * width);
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      table[i * width + j] = eq(i, j)
        ? table[(i + 1) * width + j + 1] + 1
        : Math.max(table[(i + 1) * width + j], table[i * width + j + 1]);
    }
  }
  const pairs: [number, number][] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (eq(i, j)) {
      pairs.push([i, j]);
      i++;
      j++;
    } else if (table[(i + 1) * width + j] >= table[i * width + j + 1]) i++;
    else j++;
  }
  return pairs;
}
