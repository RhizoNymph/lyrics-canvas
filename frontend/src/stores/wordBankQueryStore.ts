import { create } from 'zustand';
import { wordBankApi } from '@/api/client';
import {
  DEFAULT_FAMILY_MIN_SCORE, DEFAULT_RHYME_MIN_SCORE, WordBankApiError, checkRelatedQuery, familiesBody, rhymesBody,
  searchRelated, toWordBankApiError,
} from '@/lib/wordBank';
import type { BankFamiliesResponse, BankRhymesResponse, RelatedWordsResponse, RhymeAnchorInput } from '@/types/wordBank';
import { useWordBankStore } from './wordBankStore';

/** A server-backed view: never fetched, in flight, a result, or a typed failure. */
export type Remote<T> =
  | { readonly kind: 'idle' }
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly value: T }
  | { readonly kind: 'failed'; readonly error: WordBankApiError };

export type RelatedState =
  | { readonly kind: 'idle' }
  | { readonly kind: 'invalid'; readonly q: string; readonly problem: 'empty' | 'too_long' }
  | { readonly kind: 'loading'; readonly q: string }
  /** `attempt` requests failed with `related_words_warming`; the next one goes after `retryAfterSeconds`. */
  | { readonly kind: 'warming'; readonly q: string; readonly attempt: number; readonly retryAfterSeconds: number }
  | { readonly kind: 'ready'; readonly q: string; readonly value: RelatedWordsResponse }
  | { readonly kind: 'failed'; readonly q: string; readonly error: WordBankApiError };

/**
 * The word bank's rhyme views (families, rhymes with) and the related-word search. Each view keeps a
 * request sequence number; a response that is not the latest is dropped. Requests always send the
 * word bank's last valid filter.
 */
interface WordBankQueryState {
  familyMinScore: number;
  families: Remote<BankFamiliesResponse>;
  rhymeMinScore: number;
  /** The typed "rhymes with" text; empty means "the one selected word". */
  rhymeText: string;
  rhymes: Remote<BankRhymesResponse>;
  related: RelatedState;

  setFamilyMinScore: (score: number) => void;
  setRhymeMinScore: (score: number) => void;
  setRhymeText: (text: string) => void;
  loadFamilies: () => Promise<void>;
  /** null clears the view (no anchor). */
  loadRhymes: (anchor: RhymeAnchorInput | null) => Promise<void>;
  searchRelated: (raw: string) => Promise<void>;
  /** Stop a related search (and its warming retries). */
  cancelRelated: () => void;
  /** "+" on a related result: add it with the add box's tags, then show it as "in bank". */
  addRelatedWord: (text: string) => Promise<void>;
  reset: () => void;
}

let familiesSeq = 0;
let rhymesSeq = 0;
let relatedSeq = 0;
let wakeRelated: ((proceed: boolean) => void) | null = null;

/** setTimeout that resolves false early when the search is cancelled or superseded. */
function relatedSleep(seq: number, ms: number): Promise<boolean> {
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      wakeRelated = null;
      resolve(seq === relatedSeq);
    }, ms);
    wakeRelated = (proceed) => {
      clearTimeout(timer);
      wakeRelated = null;
      resolve(proceed);
    };
  });
}

function stopRelatedWait(): void {
  wakeRelated?.(false);
}

const INITIAL = {
  familyMinScore: DEFAULT_FAMILY_MIN_SCORE,
  families: { kind: 'idle' } as Remote<BankFamiliesResponse>,
  rhymeMinScore: DEFAULT_RHYME_MIN_SCORE,
  rhymeText: '',
  rhymes: { kind: 'idle' } as Remote<BankRhymesResponse>,
  related: { kind: 'idle' } as RelatedState,
};

export const useWordBankQueryStore = create<WordBankQueryState>((set, get) => ({
  ...INITIAL,

  setFamilyMinScore: (score) => set({ familyMinScore: Math.min(1, Math.max(0, score)) }),
  setRhymeMinScore: (score) => set({ rhymeMinScore: Math.min(1, Math.max(0, score)) }),
  setRhymeText: (text) => set({ rhymeText: text }),

  loadFamilies: async () => {
    familiesSeq += 1;
    const seq = familiesSeq;
    set({ families: { kind: 'loading' } });
    const body = familiesBody(useWordBankStore.getState().filter, get().familyMinScore);
    try {
      const value = await wordBankApi.families(body);
      if (seq === familiesSeq) set({ families: { kind: 'ready', value } });
    } catch (err) {
      if (seq === familiesSeq) set({ families: { kind: 'failed', error: toWordBankApiError(err, 'families') } });
    }
  },

  loadRhymes: async (anchor) => {
    rhymesSeq += 1;
    const seq = rhymesSeq;
    if (anchor === null) {
      set({ rhymes: { kind: 'idle' } });
      return;
    }
    set({ rhymes: { kind: 'loading' } });
    const body = rhymesBody(anchor, useWordBankStore.getState().filter, get().rhymeMinScore);
    try {
      const value = await wordBankApi.rhymes(body);
      if (seq === rhymesSeq) set({ rhymes: { kind: 'ready', value } });
    } catch (err) {
      if (seq === rhymesSeq) set({ rhymes: { kind: 'failed', error: toWordBankApiError(err, 'rhymes') } });
    }
  },

  searchRelated: async (raw) => {
    stopRelatedWait();
    relatedSeq += 1;
    const seq = relatedSeq;
    const check = checkRelatedQuery(raw);
    if (!check.ok) {
      set({ related: check.problem === 'empty' ? { kind: 'idle' } : { kind: 'invalid', q: raw, problem: check.problem } });
      return;
    }
    const q = check.q;
    set({ related: { kind: 'loading', q } });
    const result = await searchRelated(q, {
      fetch: (query) => wordBankApi.related(query),
      sleep: (ms) => relatedSleep(seq, ms),
      onWarming: (attempt, retryAfterSeconds) => {
        if (seq === relatedSeq) set({ related: { kind: 'warming', q, attempt, retryAfterSeconds } });
      },
    });
    if (seq !== relatedSeq) return;
    if (result.ok) set({ related: { kind: 'ready', q, value: result.value } });
    else if (result.error !== 'cancelled') set({ related: { kind: 'failed', q, error: result.error } });
  },

  cancelRelated: () => {
    relatedSeq += 1;
    stopRelatedWait();
    set({ related: { kind: 'idle' } });
  },

  addRelatedWord: async (text) => {
    const bank = useWordBankStore.getState();
    const result = await bank.addFromText(text, 'related', bank.addTagIds);
    if (!result.ok) return;
    const word = [...result.value.created, ...result.value.existing][0];
    if (word !== undefined) set({ related: markRelatedInBank(get().related, text, word.id) });
  },

  reset: () => {
    familiesSeq += 1;
    rhymesSeq += 1;
    relatedSeq += 1;
    stopRelatedWait();
    set({ ...INITIAL });
  },
}));

/**
 * After a word is added from the related results, mark it "in bank" without a refetch. `bank_word_id`
 * comes from the matching created or existing word (ignoring case).
 */
export function markRelatedInBank(state: RelatedState, text: string, wordId: string): RelatedState {
  if (state.kind !== 'ready') return state;
  const lower = text.toLowerCase();
  const results = state.value.results.map((r) => (r.text.toLowerCase() === lower ? { ...r, bank_word_id: wordId } : r));
  return { ...state, value: { ...state.value, results } };
}
