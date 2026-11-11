/**
 * Opening a canvas focused on one snippet (`/canvas/:id?snippet=<sid>`, rest-search contract §3b): what to
 * clear so it shows, and where the viewport goes. The tag filter is never changed; when it still hides the
 * snippet the page says so.
 */
import type { Snippet } from '@/types';
import { isSnippetVisible } from '@/lib/canvas/visibility';
import type { TagExprOrEmpty } from '@/lib/tagExpr';
import { isSearchActive, matchesSearch } from './match';
import type { SearchQuery } from './match';

export const FOCUS_PARAM = 'snippet';
export const HIDDEN_BY_TAG_FILTER = 'Hidden by the tag filter';

export type FocusPlan =
  | { readonly kind: 'missing' }
  | { readonly kind: 'focus'; readonly clearSearch: boolean; readonly hiddenByTag: boolean };

export function planSnippetFocus(snippet: Snippet | undefined, tagFilter: TagExprOrEmpty, search: SearchQuery): FocusPlan {
  if (snippet === undefined) return { kind: 'missing' };
  return {
    kind: 'focus',
    clearSearch: isSearchActive(search) && !matchesSearch(search, snippet.raw_text),
    hiddenByTag: !isSnippetVisible(snippet, tagFilter),
  };
}

/** The viewport offset (canvas units) that puts the snippet's centre in the middle of a view of `view` px. */
export function viewportCentredOn(
  snippet: Pick<Snippet, 'position_x' | 'position_y' | 'width' | 'height'>,
  view: { readonly width: number; readonly height: number },
  zoom: number,
): { x: number; y: number } {
  return {
    x: view.width / (2 * zoom) - (snippet.position_x + snippet.width / 2),
    y: view.height / (2 * zoom) - (snippet.position_y + snippet.height / 2),
  };
}

export function snippetFocusParam(params: URLSearchParams): string | null {
  const id = params.get(FOCUS_PARAM);
  return id === null || id.trim() === '' ? null : id;
}

/** The route of a canvas, focused on a snippet when given. */
export function canvasPath(canvasId: string, snippetId?: string): string {
  const base = `/canvas/${encodeURIComponent(canvasId)}`;
  return snippetId === undefined ? base : `${base}?${FOCUS_PARAM}=${encodeURIComponent(snippetId)}`;
}

/** What focusing a snippet does to the page (bound to the stores by hooks/useSnippetFocus.ts). */
export interface FocusPorts {
  readonly snippets: ReadonlyMap<string, Snippet>;
  readonly tagFilter: TagExprOrEmpty;
  readonly search: SearchQuery;
  readonly listMode: boolean;
  clearSearch(): void;
  select(snippetId: string): void;
  /** List mode: scroll the row into view. */
  reveal(snippetId: string): void;
  /** Canvas mode: centre the viewport on the snippet. */
  centre(snippet: Snippet): void;
  notify(text: string, tone: 'info' | 'error'): void;
}

export const SNIPPET_NOT_ON_CANVAS = 'That snippet is no longer on this canvas';

export function runSnippetFocus(snippetId: string, ports: FocusPorts): FocusPlan {
  const snippet = ports.snippets.get(snippetId);
  const plan = planSnippetFocus(snippet, ports.tagFilter, ports.search);
  if (plan.kind === 'missing' || snippet === undefined) {
    ports.notify(SNIPPET_NOT_ON_CANVAS, 'error');
    return plan;
  }
  if (plan.clearSearch) ports.clearSearch();
  ports.select(snippetId);
  if (ports.listMode) ports.reveal(snippetId);
  else ports.centre(snippet);
  if (plan.hiddenByTag) ports.notify(HIDDEN_BY_TAG_FILTER, 'info');
  return plan;
}
