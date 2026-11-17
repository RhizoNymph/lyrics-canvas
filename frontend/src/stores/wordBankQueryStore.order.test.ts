import { describe, it, expect, vi, beforeEach } from 'vitest';
import { wordBankApi } from '@/api/client';
import { useWordBankQueryStore } from './wordBankQueryStore';
import { useWordBankStore } from './wordBankStore';
import { useRhymeOrderStore } from './rhymeOrderStore';
import { EMPTY_FILTER } from '@/lib/tagFilter';
import { EMPTY_SELECTION } from '@/lib/wordBank';
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
const anchor = { text: 'tune', rhyme_key: ['UW1', 'N'], syllable_count: 1 };

beforeEach(() => {
  vi.clearAllMocks();
  q().reset();
  useRhymeOrderStore.setState({ order: 'rhyme' });
  useWordBankStore.setState({ words: [bw('a', 'tune', 1)], filter: EMPTY_FILTER, filterRestored: true, selection: EMPTY_SELECTION, addTagIds: [], notice: null, revision: 0 });
});

describe('rhymes with: order', () => {
  it('starts in rhyme order and sends no order field', async () => {
    expect(q().rhymesOrder).toBe('rhyme');
    api.rhymes.mockResolvedValueOnce({ anchor, matches: [], order_applied: 'rhyme' });
    await q().loadRhymes({ kind: 'word', wordId: 'a' });
    expect(api.rhymes).toHaveBeenLastCalledWith({ word_id: 'a', min_score: 0.5 });
    expect(q().rhymesOrder).toBe('rhyme');
  });

  it('sends the viewer order and remembers which order was asked', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    const matches = [{ word: bw('b', 'moon'), score: 0.97, match_depth: 2, band: 'perfect' as const, similarity: 0.42 }];
    api.rhymes.mockResolvedValueOnce({ anchor, matches, order_applied: 'meaning' });
    await q().loadRhymes({ kind: 'text', text: 'tune' });
    expect(api.rhymes).toHaveBeenLastCalledWith({ text: 'tune', min_score: 0.5, order: 'meaning' });
    expect(q().rhymesOrder).toBe('meaning');
    const r = q().rhymes;
    expect(r.kind === 'ready' && r.value.matches[0]).toMatchObject({ band: 'perfect', similarity: 0.42 });
  });

  it('reset goes back to rhyme', async () => {
    useRhymeOrderStore.setState({ order: 'meaning' });
    api.rhymes.mockResolvedValueOnce({ anchor, matches: [] });
    await q().loadRhymes({ kind: 'word', wordId: 'a' });
    q().reset();
    expect(q().rhymesOrder).toBe('rhyme');
  });
});
