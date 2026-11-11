/**
 * `GET /api/search` (rest-search contract §3b): the caller's canvases and snippets matching a query. Built
 * over an injected axios instance (tests use a mock adapter); `client.ts` binds it to the app instance. Rejects
 * with a `SearchError` only.
 */
import type { AxiosInstance } from 'axios';
import { toSearchError } from '@/lib/search/errors';
import type { GlobalSearchResponse } from '@/types/search';

export interface SearchApi {
  /** `q` is sent as given (the caller trims and checks 1..200); `limit` (1..200, server default 50) caps each list. */
  searchAll(q: string, options?: { limit?: number; signal?: AbortSignal }): Promise<GlobalSearchResponse>;
}

export function createSearchApi(http: AxiosInstance): SearchApi {
  return {
    searchAll: async (q, options = {}) => {
      const params: Record<string, string | number> = { q };
      if (options.limit !== undefined) params.limit = options.limit;
      try {
        return (await http.get<GlobalSearchResponse>('/api/search', { params, signal: options.signal })).data;
      } catch (err) {
        throw toSearchError(err);
      }
    },
  };
}
