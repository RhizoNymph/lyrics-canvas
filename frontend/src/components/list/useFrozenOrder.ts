import { useRef } from 'react';
import { applyFrozenOrder } from '@/lib/listMode';

/**
 * Rendered row order. While `frozen` is true (an editor is open) rows keep the order captured when it
 * opened, so saves that bump `content_updated_at` do not move rows under the pointer; the re-sort lands
 * once the editor closes (after the save's server response is in the store).
 */
export function useFrozenOrder(sortedIds: ReadonlyArray<string>, frozen: boolean): string[] {
  const captured = useRef<string[] | null>(null);
  if (frozen && captured.current === null) captured.current = [...sortedIds];
  if (!frozen) captured.current = null;
  return applyFrozenOrder(sortedIds, captured.current);
}
