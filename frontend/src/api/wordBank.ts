/**
 * Word bank endpoints (contract §3). Built over an injected axios instance so tests can drive it with a
 * mock adapter; `client.ts` binds it to the authenticated app instance. Every function rejects with a
 * `WordBankApiError` only.
 */
import type { AxiosInstance } from 'axios';
import { toWordBankApiError } from '@/lib/wordBank/errors';
import type { WordBankOperation } from '@/lib/wordBank/errors';
import type {
  BankFamiliesBody, BankFamiliesResponse, BankRhymesBody, BankRhymesResponse, BankWord, BankWordCreateBody,
  BankWordCreateResponse, RelatedWordsResponse, WordTagOp, WordTagsResponse,
} from '@/types/wordBank';

async function wrap<T>(operation: WordBankOperation, call: () => Promise<{ data: T }>): Promise<T> {
  try {
    return (await call()).data;
  } catch (err) {
    throw toWordBankApiError(err, operation);
  }
}

export interface WordBankApi {
  /** The whole bank, newest first. */
  list(signal?: AbortSignal): Promise<BankWord[]>;
  add(body: BankWordCreateBody): Promise<BankWordCreateResponse>;
  rename(id: string, text: string): Promise<BankWord>;
  delete(ids: readonly string[]): Promise<void>;
  setTag(wordIds: readonly string[], tagId: string, op: WordTagOp): Promise<WordTagsResponse>;
  rhymes(body: BankRhymesBody, signal?: AbortSignal): Promise<BankRhymesResponse>;
  families(body: BankFamiliesBody, signal?: AbortSignal): Promise<BankFamiliesResponse>;
  related(q: string, limit?: number, signal?: AbortSignal): Promise<RelatedWordsResponse>;
}

export const RELATED_DEFAULT_LIMIT = 30;

export function createWordBankApi(http: AxiosInstance): WordBankApi {
  return {
    list: (signal) => wrap('load_words', () => http.get<BankWord[]>('/api/word-bank', { signal })),
    add: (body) => wrap('add_words', () => http.post<BankWordCreateResponse>('/api/word-bank', body)),
    rename: (id, text) => wrap('rename_word', () => http.put<BankWord>(`/api/word-bank/${encodeURIComponent(id)}`, { text })),
    delete: async (ids) => {
      await wrap('delete_words', () => http.post('/api/word-bank/delete', { ids: [...ids] }));
    },
    setTag: (wordIds, tagId, op) =>
      wrap(op === 'link' ? 'link_words' : 'unlink_words', () =>
        http.post<WordTagsResponse>('/api/word-bank/tags', { word_ids: [...wordIds], tag_id: tagId, op }),
      ),
    rhymes: (body, signal) => wrap('rhymes', () => http.post<BankRhymesResponse>('/api/word-bank/rhymes', body, { signal })),
    families: (body, signal) => wrap('families', () => http.post<BankFamiliesResponse>('/api/word-bank/families', body, { signal })),
    related: (q, limit = RELATED_DEFAULT_LIMIT, signal) =>
      wrap('related', () => http.get<RelatedWordsResponse>('/api/word-bank/related', { params: { q, limit }, signal })),
  };
}
