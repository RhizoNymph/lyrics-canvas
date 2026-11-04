import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { wordBankApi } from '@/api/client';
import { useWordBankQueryStore, markRelatedInBank } from './wordBankQueryStore';
import { useWordBankStore } from './wordBankStore';
import { EMPTY_FILTER, filterReducer } from '@/lib/tagFilter';
import { EMPTY_SELECTION, WordBankApiError } from '@/lib/wordBank';
import { bw } from '@/lib/wordBank/fixtures';

vi.mock('@/api/client', () => ({
  wordBankApi: { list: vi.fn(), add: vi.fn(), rename: vi.fn(), delete: vi.fn(), setTag: vi.fn(), rhymes: vi.fn(), families: vi.fn(), related: vi.fn() },
  tagApi: {},
  tagPresetApi: {},
  nlpApi: {},
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
}));

const api = vi.mocked(wordBankApi);
const q = () => useWordBankQueryStore.getState();

const warming = (s: number) => new WordBankApiError({ kind: 'related_words_warming', retryAfterSeconds: s }, 'related', 'w', 503);
const relatedOk = { query: 'sea', results: [{ text: 'ocean', similarity: 0.71, bank_word_id: null }, { text: 'wave', similarity: 0.6, bank_word_id: 'w9' }] };

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  q().reset();
  useWordBankStore.setState({
    words: [bw('a', 'tune', 1)],
    filter: filterReducer(EMPTY_FILTER, { type: 'edit', text: 'love   night' }),
    filterRestored: true,
    selection: EMPTY_SELECTION,
    addTagIds: ['t1'],
    notice: null,
    revision: 0,
  });
});

afterEach(() => vi.useRealTimers());

describe('families', () => {
  it('defaults to 0.8 and sends the normalized filter', async () => {
    expect(q().familyMinScore).toBe(0.8);
    api.families.mockResolvedValueOnce({ families: [], unmatched: [] });
    await q().loadFamilies();
    expect(api.families).toHaveBeenCalledWith({ min_score: 0.8, tag_filter: 'love & night' });
    expect(q().families).toEqual({ kind: 'ready', value: { families: [], unmatched: [] } });
  });

  it('drops a stale response', async () => {
    const first = deferred<{ families: never[]; unmatched: never[] }>();
    api.families.mockReturnValueOnce(first.promise).mockResolvedValueOnce({ families: [], unmatched: [bw('a', 'tune')] });
    const p1 = q().loadFamilies();
    q().setFamilyMinScore(0.6);
    await q().loadFamilies();
    first.resolve({ families: [], unmatched: [] });
    await p1;
    const f = q().families;
    expect(f.kind === 'ready' && f.value.unmatched).toHaveLength(1);
    expect(api.families.mock.calls[1][0].min_score).toBe(0.6);
  });

  it('keeps too_many_words typed', async () => {
    api.families.mockRejectedValueOnce(new WordBankApiError({ kind: 'too_many_words' }, 'families', 'x', 422));
    await q().loadFamilies();
    const f = q().families;
    expect(f.kind === 'failed' && f.error.kind).toBe('too_many_words');
  });
});

describe('rhymes with', () => {
  it('defaults to 0.5 and sends exactly one anchor', async () => {
    expect(q().rhymeMinScore).toBe(0.5);
    api.rhymes.mockResolvedValue({ anchor: { text: 'tune', rhyme_key: ['UW1', 'N'], syllable_count: 1 }, matches: [] });
    await q().loadRhymes({ kind: 'word', wordId: 'a' });
    expect(api.rhymes).toHaveBeenLastCalledWith({ word_id: 'a', min_score: 0.5, tag_filter: 'love & night' });
    await q().loadRhymes({ kind: 'text', text: 'moon' });
    expect(api.rhymes).toHaveBeenLastCalledWith({ text: 'moon', min_score: 0.5, tag_filter: 'love & night' });
  });

  it('clears without an anchor', async () => {
    await q().loadRhymes(null);
    expect(q().rhymes).toEqual({ kind: 'idle' });
    expect(api.rhymes).not.toHaveBeenCalled();
  });
});

describe('related', () => {
  it('ignores an empty query and flags a too long one', async () => {
    await q().searchRelated('   ');
    expect(q().related).toEqual({ kind: 'idle' });
    await q().searchRelated('a'.repeat(65));
    expect(q().related.kind).toBe('invalid');
    expect(api.related).not.toHaveBeenCalled();
  });

  it('shows results', async () => {
    api.related.mockResolvedValueOnce(relatedOk);
    await q().searchRelated(' sea ');
    expect(api.related).toHaveBeenCalledWith('sea');
    expect(q().related).toEqual({ kind: 'ready', q: 'sea', value: relatedOk });
  });

  it('shows the warming state and retries after Retry-After', async () => {
    vi.useFakeTimers();
    api.related.mockRejectedValueOnce(warming(10)).mockResolvedValueOnce(relatedOk);
    const p = q().searchRelated('sea');
    await vi.advanceTimersByTimeAsync(0);
    expect(q().related).toEqual({ kind: 'warming', q: 'sea', attempt: 1, retryAfterSeconds: 10 });
    await vi.advanceTimersByTimeAsync(9_999);
    expect(api.related).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await p;
    expect(api.related).toHaveBeenCalledTimes(2);
    expect(q().related.kind).toBe('ready');
  });

  it('stops after 10 tries', async () => {
    vi.useFakeTimers();
    api.related.mockRejectedValue(warming(1));
    const p = q().searchRelated('sea');
    await vi.advanceTimersByTimeAsync(20_000);
    await p;
    expect(api.related).toHaveBeenCalledTimes(10);
    const r = q().related;
    expect(r.kind === 'failed' && r.error.kind).toBe('related_words_warming');
  });

  it('a new search cancels the retries of the previous one', async () => {
    vi.useFakeTimers();
    api.related.mockRejectedValueOnce(warming(10)).mockResolvedValueOnce({ query: 'sky', results: [] });
    const p1 = q().searchRelated('sea');
    await vi.advanceTimersByTimeAsync(0);
    await q().searchRelated('sky');
    await p1;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.related).toHaveBeenCalledTimes(2);
    expect(q().related).toEqual({ kind: 'ready', q: 'sky', value: { query: 'sky', results: [] } });
  });

  it('cancelRelated stops waiting', async () => {
    vi.useFakeTimers();
    api.related.mockRejectedValue(warming(10));
    const p = q().searchRelated('sea');
    await vi.advanceTimersByTimeAsync(0);
    q().cancelRelated();
    await p;
    await vi.advanceTimersByTimeAsync(60_000);
    expect(api.related).toHaveBeenCalledTimes(1);
    expect(q().related).toEqual({ kind: 'idle' });
  });

  it('shows unavailable without retrying', async () => {
    api.related.mockRejectedValueOnce(new WordBankApiError({ kind: 'related_words_unavailable' }, 'related', 'x', 503));
    await q().searchRelated('sea');
    const r = q().related;
    expect(r.kind === 'failed' && r.error.kind).toBe('related_words_unavailable');
    expect(api.related).toHaveBeenCalledTimes(1);
  });

  it('"+" adds with the add box tags and marks the result in bank', async () => {
    api.related.mockResolvedValueOnce(relatedOk);
    await q().searchRelated('sea');
    api.add.mockResolvedValueOnce({ created: [bw('n1', 'ocean', 5)], existing: [] });
    await q().addRelatedWord('ocean');
    expect(api.add).toHaveBeenCalledWith({ words: ['ocean'], tag_ids: ['t1'] });
    const r = q().related;
    expect(r.kind === 'ready' && r.value.results[0].bank_word_id).toBe('n1');
    expect(useWordBankStore.getState().notice?.text).toBe('Added 1, 0 already in bank');
  });
});

describe('markRelatedInBank', () => {
  it('matches ignoring case and leaves other states alone', () => {
    const ready = { kind: 'ready' as const, q: 'sea', value: relatedOk };
    const next = markRelatedInBank(ready, 'OCEAN', 'x');
    expect(next.kind === 'ready' && next.value.results[0].bank_word_id).toBe('x');
    const idle = { kind: 'idle' as const };
    expect(markRelatedInBank(idle, 'a', 'b')).toBe(idle);
  });
});
