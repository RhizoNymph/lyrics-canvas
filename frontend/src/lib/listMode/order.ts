/**
 * List-mode ordering: last content edit first (`content_updated_at`, bumped by the server on text /
 * type / grid changes only, never on moves), then newest created, then id. A total, deterministic order.
 */
import type { Snippet } from '@/types';

type Orderable = Pick<Snippet, 'id' | 'content_updated_at' | 'created_at'>;

/** Epoch ms of an ISO-8601 timestamp; missing or unparseable values sort as the oldest. */
function instant(iso: string | undefined): number {
  if (!iso) return Number.NEGATIVE_INFINITY;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? Number.NEGATIVE_INFINITY : ms;
}

function descending(a: number, b: number): number {
  if (a === b) return 0;
  return a > b ? -1 : 1;
}

export function compareForList(a: Orderable, b: Orderable): number {
  return (
    descending(instant(a.content_updated_at), instant(b.content_updated_at)) ||
    descending(instant(a.created_at), instant(b.created_at)) ||
    (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)
  );
}

/** A sorted copy; the input is untouched. */
export function sortForList<T extends Orderable>(snippets: ReadonlyArray<T>): T[] {
  return [...snippets].sort(compareForList);
}

/**
 * Row order to render. While `frozen` (the order captured when an edit began) is set, rows keep their
 * frozen positions so nothing jumps under the pointer: gone ids drop out and ids that appeared since
 * the freeze go on top in their sorted order. With `frozen` null this is just `sortedIds`.
 */
export function applyFrozenOrder(sortedIds: ReadonlyArray<string>, frozen: ReadonlyArray<string> | null): string[] {
  if (frozen === null) return [...sortedIds];
  const present = new Set(sortedIds);
  const frozenSet = new Set(frozen);
  const fresh = sortedIds.filter((id) => !frozenSet.has(id));
  return [...fresh, ...frozen.filter((id) => present.has(id))];
}
