/**
 * `POST /api/nlp/rhyme-pattern/detect` and `/suggest` (rhyme-pattern contract §1.2). Built over an injected
 * axios instance (tests use a mock adapter); `client.ts` binds it to the app instance. Rejects with a
 * `PatternError` only.
 */
import type { AxiosInstance } from 'axios';
import { toPatternError } from '@/lib/rhyme/pattern/errors';
import type { PatternOperation } from '@/lib/rhyme/pattern/errors';
import type { PatternDetectRequest, PatternDetectResponse, PatternSuggestRequest, PatternSuggestResponse } from '@/types/rhymePattern';

export interface RhymePatternApi {
  detect(req: PatternDetectRequest): Promise<PatternDetectResponse>;
  /** `operation` labels a failure: 'suggest' for a first page, 'more' for a page of one slot or of line matches. */
  suggest(req: PatternSuggestRequest, operation?: Extract<PatternOperation, 'suggest' | 'more'>): Promise<PatternSuggestResponse>;
}

export function createRhymePatternApi(http: AxiosInstance): RhymePatternApi {
  return {
    detect: async (req) => {
      try {
        return (await http.post<PatternDetectResponse>('/api/nlp/rhyme-pattern/detect', req)).data;
      } catch (err) {
        throw toPatternError(err, 'detect');
      }
    },
    suggest: async (req, operation = 'suggest') => {
      try {
        return (await http.post<PatternSuggestResponse>('/api/nlp/rhyme-pattern/suggest', req)).data;
      } catch (err) {
        throw toPatternError(err, operation);
      }
    },
  };
}
