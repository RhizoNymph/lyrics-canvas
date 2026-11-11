/**
 * The canvas list page's search across canvases (contract §3b): debounced (about 250 ms) requests to
 * `GET /api/search`, the latest query only (older requests are aborted and their late answers dropped).
 * Blank input turns the search off (the page shows its normal list).
 */
import type { GlobalSearchResponse } from '@/types/search';
import { SearchError, checkGlobalQuery, toSearchError } from './errors';
import { parseSearchQuery } from './match';
import type { SearchQuery } from './match';

export const GLOBAL_SEARCH_DEBOUNCE_MS = 250;

export type GlobalSearchView =
  | { readonly kind: 'off' }
  /** `previous`: the last results, kept on screen while this query loads. */
  | { readonly kind: 'loading'; readonly q: string; readonly previous: GlobalSearchResponse | null }
  | { readonly kind: 'results'; readonly q: string; readonly query: SearchQuery; readonly result: GlobalSearchResponse }
  | { readonly kind: 'failed'; readonly q: string; readonly error: SearchError };

export interface GlobalSearchDeps {
  search: (q: string, signal: AbortSignal) => Promise<GlobalSearchResponse>;
  onView: (view: GlobalSearchView) => void;
}

export interface GlobalSearchController {
  setInput: (raw: string) => void;
  dispose: () => void;
}

export function createGlobalSearch(deps: GlobalSearchDeps): GlobalSearchController {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let inFlight: AbortController | null = null;
  let seq = 0;
  let lastResult: GlobalSearchResponse | null = null;

  const cancel = () => {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    inFlight?.abort();
    inFlight = null;
  };

  const run = async (q: string, mine: number) => {
    const abort = new AbortController();
    inFlight = abort;
    try {
      const result = await deps.search(q, abort.signal);
      if (mine !== seq) return;
      lastResult = result;
      deps.onView({ kind: 'results', q, query: parseSearchQuery(q), result });
    } catch (err) {
      if (mine !== seq) return;
      deps.onView({ kind: 'failed', q, error: toSearchError(err) });
    } finally {
      if (inFlight === abort) inFlight = null;
    }
  };

  return {
    setInput: (raw) => {
      cancel();
      const mine = ++seq;
      const check = checkGlobalQuery(raw);
      if (check.kind === 'blank') {
        lastResult = null;
        deps.onView({ kind: 'off' });
        return;
      }
      if (check.kind === 'invalid') {
        deps.onView({ kind: 'failed', q: raw.trim(), error: check.error });
        return;
      }
      deps.onView({ kind: 'loading', q: check.q, previous: lastResult });
      timer = setTimeout(() => {
        timer = null;
        void run(check.q, mine);
      }, GLOBAL_SEARCH_DEBOUNCE_MS);
    },
    dispose: () => {
      seq++;
      cancel();
    },
  };
}
