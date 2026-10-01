import type { Snippet } from '@/types';
import type { MergeMode } from './types';

/**
 * Canvas-position order for a merge. Vertical reads top-to-bottom (y, then x);
 * horizontal reads left-to-right (x, then y). Ties fall back to id so the order is total.
 */
export function orderByPosition<T extends Snippet>(snippets: readonly T[], mode: MergeMode): T[] {
  const primary: keyof Snippet = mode === 'vertical' ? 'position_y' : 'position_x';
  const secondary: keyof Snippet = mode === 'vertical' ? 'position_x' : 'position_y';
  return [...snippets].sort(
    (a, b) =>
      (a[primary] as number) - (b[primary] as number) ||
      (a[secondary] as number) - (b[secondary] as number) ||
      a.id.localeCompare(b.id)
  );
}
