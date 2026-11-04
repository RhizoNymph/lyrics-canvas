import { describe, it, expect, vi } from 'vitest';
import { EMPTY_FILTER, filterReducer } from '@/lib/tagFilter';
import { WordBankApiError } from './errors';
import {
  DEFAULT_FAMILY_MIN_SCORE, DEFAULT_RHYME_MIN_SCORE, MAX_RELATED_TRIES, checkRelatedQuery, familiesBody, rhymesBody,
  searchRelated, tagFilterParam,
} from './queries';

const filter = (text: string) => filterReducer(EMPTY_FILTER, { type: 'edit', text });

describe('tagFilterParam', () => {
  it('is omitted without a filter and normalized with one', () => {
    expect(tagFilterParam(EMPTY_FILTER)).toBeUndefined();
    expect(tagFilterParam(filter('love   night'))).toBe('love & night');
  });

  it('uses the last valid filter while the text is invalid', () => {
    const f = filterReducer(filter('love'), { type: 'edit', text: 'love &' });
    expect(f.error).not.toBeNull();
    expect(tagFilterParam(f)).toBe('love');
  });
});

describe('request bodies', () => {
  it('defaults', () => {
    expect(DEFAULT_FAMILY_MIN_SCORE).toBe(0.8);
    expect(DEFAULT_RHYME_MIN_SCORE).toBe(0.5);
  });

  it('families send min_score and the filter', () => {
    expect(familiesBody(EMPTY_FILTER, 0.8)).toEqual({ min_score: 0.8 });
    expect(familiesBody(filter('love'), 0.7)).toEqual({ min_score: 0.7, tag_filter: 'love' });
  });

  it('rhymes send exactly one of word_id and text', () => {
    expect(rhymesBody({ kind: 'word', wordId: 'w1' }, EMPTY_FILTER, 0.5)).toEqual({ word_id: 'w1', min_score: 0.5 });
    expect(rhymesBody({ kind: 'text', text: '  night ' }, filter('a | b'), 0.6)).toEqual({ text: 'night', min_score: 0.6, tag_filter: 'a | b' });
  });

  it('clamps scores to 0..1', () => {
    expect(familiesBody(EMPTY_FILTER, 1.4).min_score).toBe(1);
    expect(rhymesBody({ kind: 'word', wordId: 'w' }, EMPTY_FILTER, -1).min_score).toBe(0);
  });
});

describe('checkRelatedQuery', () => {
  it('needs 1..64 code points after a strip, phrases allowed', () => {
    expect(checkRelatedQuery('  ocean at night ')).toEqual({ ok: true, q: 'ocean at night' });
    expect(checkRelatedQuery('   ').ok).toBe(false);
    expect(checkRelatedQuery('a'.repeat(64)).ok).toBe(true);
    expect(checkRelatedQuery('a'.repeat(65)).ok).toBe(false);
  });
});

describe('searchRelated', () => {
  const response = { query: 'sea', results: [{ text: 'ocean', similarity: 0.8, bank_word_id: null }] };
  const warming = (s = 2) => new WordBankApiError({ kind: 'related_words_warming', retryAfterSeconds: s }, 'related', 'w', 503);

  it('returns the results', async () => {
    const fetch = vi.fn().mockResolvedValue(response);
    const r = await searchRelated('sea', { fetch, sleep: vi.fn(), onWarming: vi.fn() });
    expect(r).toEqual({ ok: true, value: response });
    expect(fetch).toHaveBeenCalledWith('sea');
  });

  it('retries while warming, waiting Retry-After each time', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(warming(3)).mockRejectedValueOnce(warming(5)).mockResolvedValueOnce(response);
    const sleep = vi.fn().mockResolvedValue(true);
    const onWarming = vi.fn();
    const r = await searchRelated('sea', { fetch, sleep, onWarming });
    expect(r.ok).toBe(true);
    expect(sleep.mock.calls).toEqual([[3000], [5000]]);
    expect(onWarming.mock.calls).toEqual([[1, 3], [2, 5]]);
    expect(fetch).toHaveBeenCalledTimes(3);
  });

  it('gives up after 10 tries with the warming error', async () => {
    const fetch = vi.fn().mockRejectedValue(warming());
    const sleep = vi.fn().mockResolvedValue(true);
    const r = await searchRelated('sea', { fetch, sleep, onWarming: vi.fn() });
    expect(MAX_RELATED_TRIES).toBe(10);
    expect(fetch).toHaveBeenCalledTimes(10);
    expect(sleep).toHaveBeenCalledTimes(9);
    expect(r.ok).toBe(false);
    if (!r.ok && r.error !== 'cancelled') expect(r.error.kind).toBe('related_words_warming');
  });

  it('stops when the sleep is cancelled', async () => {
    const fetch = vi.fn().mockRejectedValue(warming());
    const sleep = vi.fn().mockResolvedValue(false);
    const r = await searchRelated('sea', { fetch, sleep, onWarming: vi.fn() });
    expect(r).toEqual({ ok: false, error: 'cancelled' });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('does not retry other errors (unavailable)', async () => {
    const unavailable = new WordBankApiError({ kind: 'related_words_unavailable' }, 'related', 'u', 503);
    const fetch = vi.fn().mockRejectedValue(unavailable);
    const r = await searchRelated('sea', { fetch, sleep: vi.fn(), onWarming: vi.fn() });
    expect(r).toEqual({ ok: false, error: unavailable });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it('types unexpected throws', async () => {
    const fetch = vi.fn().mockRejectedValue(new Error('boom'));
    const r = await searchRelated('sea', { fetch, sleep: vi.fn(), onWarming: vi.fn() });
    expect(r.ok).toBe(false);
    if (!r.ok && r.error !== 'cancelled') expect(r.error).toBeInstanceOf(WordBankApiError);
  });
});

describe('resolveRhymeAnchor', () => {
  it('prefers typed text, else exactly one selected word', async () => {
    const { resolveRhymeAnchor } = await import('./queries');
    expect(resolveRhymeAnchor(' night ', new Set(['a']))).toEqual({ kind: 'text', text: 'night' });
    expect(resolveRhymeAnchor('', new Set(['a']))).toEqual({ kind: 'word', wordId: 'a' });
    expect(resolveRhymeAnchor('  ', new Set(['a', 'b']))).toBeNull();
    expect(resolveRhymeAnchor('', new Set())).toBeNull();
  });
});
