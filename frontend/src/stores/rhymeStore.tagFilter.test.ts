import { describe, it, expect, vi, beforeEach } from 'vitest';
import { nlpApi } from '@/api/client';
import { useRhymeStore } from './rhymeStore';
import { useCanvasStore } from './canvasStore';
import { snippet } from '@/lib/merge/fixtures';

vi.mock('@/api/client', () => ({
  nlpApi: { rhymeSuggest: vi.fn() },
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
  tagPresetApi: {},
}));

const rhymeSuggest = vi.mocked(nlpApi.rhymeSuggest);
const empty = { data: { anchors: [], candidates: [], total: 0, elapsed_ms: 1 } } as never;

beforeEach(() => {
  vi.clearAllMocks();
  useCanvasStore.setState({ canvasId: 'c1', snippets: new Map([['a', snippet('a', 'door hinge')]]), selectedSnippetIds: new Set(['a']) });
  useRhymeStore.getState().exit();
  useRhymeStore.setState({ active: true, tagFilterText: '', sources: { notes: true, dictionary: true } });
});

describe('rhyme notes tag filter', () => {
  it('omits tag_filter when empty', async () => {
    rhymeSuggest.mockResolvedValueOnce(empty);
    useRhymeStore.getState().setTagFilterText('   ');
    await useRhymeStore.getState().runQuery();
    expect(rhymeSuggest.mock.calls[0][0]).not.toHaveProperty('tag_filter');
  });

  it('sends the normalized expression', async () => {
    rhymeSuggest.mockResolvedValueOnce(empty);
    useRhymeStore.getState().setTagFilterText('love  and not draft');
    await useRhymeStore.getState().runQuery();
    expect(rhymeSuggest.mock.calls[0][0].tag_filter).toBe('love & !draft');
  });

  it('blocks an invalid expression locally with a typed error', async () => {
    useRhymeStore.setState({ candidates: [{ id: 'old' } as never] });
    useRhymeStore.getState().setTagFilterText('love &');
    await useRhymeStore.getState().runQuery();
    expect(rhymeSuggest).not.toHaveBeenCalled();
    const s = useRhymeStore.getState();
    expect(s.error).toMatchObject({ kind: 'invalid_tag_filter', expressionError: { code: 'unexpected_end', position: 6 } });
    expect(s.candidates).toEqual([]);
    expect(s.loading).toBe(false);
  });

  it('loadMore never sends an invalid filter', async () => {
    useRhymeStore.setState({ total: 100, offset: 0, tagFilterText: '(' });
    await useRhymeStore.getState().loadMore();
    expect(rhymeSuggest).not.toHaveBeenCalled();
  });

  it('follows tag renames and survives exit like other params', () => {
    useRhymeStore.getState().setTagFilterText('Love | night');
    useRhymeStore.getState().renameTagInFilter('love', 'romance');
    expect(useRhymeStore.getState().tagFilterText).toBe('romance | night');
    useRhymeStore.getState().exit();
    expect(useRhymeStore.getState().tagFilterText).toBe('romance | night');
  });
});
