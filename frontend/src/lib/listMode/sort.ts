/**
 * The list view's sort menu: Last edited (the original list order), Created, A-Z and Custom (the placement's
 * `list_rank`, shared by web and Android through the server). Every comparator is a total order.
 */
import type { Snippet } from '@/types';
import { displayText } from '@/lib/grid/restMarks';
import { compareForList } from './order';
import { compareRanks } from './rank';

export type ListSort = 'last_edited' | 'created' | 'a_z' | 'custom';

/** Menu order. */
export const LIST_SORTS: readonly ListSort[] = ['last_edited', 'created', 'a_z', 'custom'];

export const LIST_SORT_LABELS: Readonly<Record<ListSort, string>> = {
  last_edited: 'Last edited',
  created: 'Created',
  a_z: 'A-Z',
  custom: 'Custom',
};

export const DEFAULT_LIST_SORT: ListSort = 'last_edited';

export function parseListSort(raw: string | null): ListSort | null {
  return raw !== null && (LIST_SORTS as readonly string[]).includes(raw) ? (raw as ListSort) : null;
}

export type Sortable = Pick<
  Snippet,
  'id' | 'placement_id' | 'raw_text' | 'list_rank' | 'created_at' | 'content_updated_at'
>;

function byId(a: Sortable, b: Sortable): number {
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

function instant(iso: string | undefined): number {
  if (!iso) return Number.NEGATIVE_INFINITY;
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? Number.NEGATIVE_INFINITY : ms;
}

function compareCreated(a: Sortable, b: Sortable): number {
  const ta = instant(a.created_at);
  const tb = instant(b.created_at);
  if (ta !== tb) return ta > tb ? -1 : 1;
  return byId(a, b);
}

const textCollator = new Intl.Collator(undefined, { sensitivity: 'base' });

/** What A-Z compares: the display text (rest marks dropped), leading/trailing whitespace trimmed. */
export function sortText(s: Pick<Snippet, 'raw_text'>): string {
  return displayText(s.raw_text).trim();
}

function compareText(a: Sortable, b: Sortable): number {
  return textCollator.compare(sortText(a), sortText(b)) || byId(a, b);
}

function compareCustom(a: Sortable, b: Sortable): number {
  return (
    compareRanks(a.list_rank, b.list_rank) ||
    (a.placement_id < b.placement_id ? -1 : a.placement_id > b.placement_id ? 1 : 0) ||
    byId(a, b)
  );
}

export function compareForSort(sort: ListSort): (a: Sortable, b: Sortable) => number {
  switch (sort) {
    case 'last_edited':
      return compareForList;
    case 'created':
      return compareCreated;
    case 'a_z':
      return compareText;
    case 'custom':
      return compareCustom;
  }
}

/** A sorted copy; the input is untouched. */
export function sortSnippets<T extends Sortable>(sort: ListSort, snippets: ReadonlyArray<T>): T[] {
  return [...snippets].sort(compareForSort(sort));
}
