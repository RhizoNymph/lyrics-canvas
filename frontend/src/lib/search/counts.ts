/** The canvas page's count lines: the tag bar's and the search box's "N of M" (both count what all filters leave). */
export interface FilterCounts {
  readonly total: number;
  /** Snippets the tag filter AND the in-canvas search show. */
  readonly shown: number;
  readonly tagActive: boolean;
  readonly searchActive: boolean;
}

export function filterCountLabels({ total, shown, tagActive, searchActive }: FilterCounts): { tag: string; search: string | null } {
  return {
    tag: tagActive || searchActive ? `${shown} of ${total} shown` : `${total} snippets`,
    search: searchActive ? `${shown} of ${total}` : null,
  };
}
