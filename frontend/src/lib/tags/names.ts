/**
 * Client-side mirror of the server's name and color rules, so obviously bad input is caught before a
 * request: names are trimmed, 1-100 code points, no control characters; colors are `#rrggbb`.
 */
import { cpLength } from '@/lib/tagExpr';

export const MAX_NAME_LENGTH = 100;
export const DEFAULT_TAG_COLOR = '#6366f1';
export const TAG_COLOR_CHOICES: readonly string[] = [
  '#ef4444', '#f59e0b', '#22c55e', '#3b82f6', '#8b5cf6', '#ec4899', '#14b8a6', '#f97316',
];

export type NameProblem = 'empty' | 'too_long' | 'control_character';
export type NameCheck = { ok: true; name: string } | { ok: false; problem: NameProblem };

// Cc (C0, DEL, C1) plus the line/paragraph separators, which Python's str.isprintable also rejects.
const CONTROL = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/u;

export function checkName(raw: string): NameCheck {
  const name = raw.trim();
  if (name.length === 0) return { ok: false, problem: 'empty' };
  if (cpLength(name) > MAX_NAME_LENGTH) return { ok: false, problem: 'too_long' };
  if (CONTROL.test(name)) return { ok: false, problem: 'control_character' };
  return { ok: true, name };
}

export function describeNameProblem(problem: NameProblem): string {
  switch (problem) {
    case 'empty':
      return 'Enter a name';
    case 'too_long':
      return `At most ${MAX_NAME_LENGTH} characters`;
    case 'control_character':
      return 'No control characters';
  }
}

export function isHexColor(value: string): boolean {
  return /^#[0-9a-fA-F]{6}$/.test(value);
}

/** Case-insensitive name order, the order the server sorts by (lower(name)). */
export function byName<T extends { name: string }>(a: T, b: T): number {
  const x = a.name.toLowerCase();
  const y = b.name.toLowerCase();
  return x < y ? -1 : x > y ? 1 : 0;
}

export function sameName(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase();
}
