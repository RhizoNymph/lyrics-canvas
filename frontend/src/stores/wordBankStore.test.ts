import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { snippetApi, tagApi, wordBankApi } from '@/api/client';
import { useWordBankStore, selectVisibleWords } from './wordBankStore';
import { useTagStore } from './tagStore';
import { useCanvasStore } from './canvasStore';
import { useTagFilterStore } from './tagFilterStore';
import { useRhymeStore } from './rhymeStore';
import { EMPTY_FILTER, WORD_BANK_FILTER_KEY } from '@/lib/tagFilter';
import { EMPTY_SELECTION, WordBankApiError } from '@/lib/wordBank';
import { bw, wtag } from '@/lib/wordBank/fixtures';
import { snippet } from '@/lib/merge/fixtures';
import type { Tag } from '@/types';

vi.mock('@/api/client', () => ({
  wordBankApi: { list: vi.fn(), add: vi.fn(), rename: vi.fn(), delete: vi.fn(), setTag: vi.fn(), rhymes: vi.fn(), families: vi.fn(), related: vi.fn() },
  tagApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn(), addToSnippet: vi.fn(), removeFromSnippet: vi.fn() },
  tagPresetApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  nlpApi: { rhymeSuggest: vi.fn() },
  snippetApi: { create: vi.fn() },
  canvasApi: {},
  groupApi: {},
}));

const api = vi.mocked(wordBankApi);
const tags = vi.mocked(tagApi);
const createSnippet = vi.mocked(snippetApi.create);

const T = (id: string, name: string, extra: Partial<Tag> = {}): Tag => ({
  id, name, color: '#111111', snippet_count: 0, word_count: 0, created_at: '', ...extra,
});
const love = wtag('t1', 'love', '#111111');
const night = wtag('t2', 'night', '#111111');

let storage: Record<string, string>;

function words() {
  return useWordBankStore.getState().words;
}
function selected() {
  return [...useWordBankStore.getState().selection.ids].sort();
}
function wordCount(id: string) {
  return useTagStore.getState().tags.find((t) => t.id === id)?.word_count;
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  storage = {};
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => (k in storage ? storage[k] : null),
    setItem: (k: string, v: string) => {
      storage[k] = v;
    },
    removeItem: (k: string) => {
      delete storage[k];
    },
  });
  useWordBankStore.setState({
    words: [bw('c', 'moon', 3, [love]), bw('b', 'night', 2, [love, night]), bw('a', 'tune', 1)],
    loadState: 'ready',
    loadError: null,
    filter: EMPTY_FILTER,
    filterRestored: true,
    selection: EMPTY_SELECTION,
    addTagIds: [],
    revision: 0,
    notice: null,
  });
  useTagStore.setState({ tags: [T('t1', 'love', { word_count: 2 }), T('t2', 'night', { word_count: 1 })], presets: [], loadState: 'ready', loadError: null });
  useCanvasStore.setState({ canvasId: 'c1', snippets: new Map(), past: [], future: [], viewport: { x: 0, y: 0, zoom: 1 }, canvasViewSize: null });
  useTagFilterStore.setState({ canvasId: null, filter: EMPTY_FILTER });
  useRhymeStore.setState({ tagFilterText: '' });
});

afterEach(() => vi.unstubAllGlobals());

describe('open / load', () => {
  it('restores the persisted filter once, then loads the bank', async () => {
    storage[WORD_BANK_FILTER_KEY] = JSON.stringify({ v: 1, text: 'love &', appliedText: 'love' });
    useWordBankStore.setState({ filterRestored: false, filter: EMPTY_FILTER, words: [] });
    api.list.mockResolvedValueOnce([bw('x', 'star', 5, [love]), bw('y', 'car', 4)]);
    const r = await useWordBankStore.getState().open();
    expect(r.ok).toBe(true);
    const s = useWordBankStore.getState();
    expect(s.filter.text).toBe('love &');
    expect(s.filter.appliedText).toBe('love');
    expect(s.loadState).toBe('ready');
    expect(selectVisibleWords(s).map((w) => w.text)).toEqual(['star']);
  });

  it('records a typed load failure', async () => {
    api.list.mockRejectedValueOnce(new WordBankApiError({ kind: 'network' }, 'load_words', 'down'));
    const r = await useWordBankStore.getState().load();
    expect(r.ok).toBe(false);
    expect(useWordBankStore.getState().loadState).toBe('failed');
    expect(useWordBankStore.getState().loadError?.kind).toBe('network');
  });
});

describe('filter', () => {
  it('filters locally with the typeless evaluator and persists the text under its own key', () => {
    useWordBankStore.getState().dispatchFilter({ type: 'edit', text: 'love & !night' });
    expect(selectVisibleWords(useWordBankStore.getState()).map((w) => w.text)).toEqual(['moon']);
    expect(JSON.parse(storage[WORD_BANK_FILTER_KEY])).toEqual({ v: 1, text: 'love & !night', appliedText: 'love & !night' });
    expect(Object.keys(storage)).toEqual([WORD_BANK_FILTER_KEY]);
  });

  it('keeps the last valid filter while the text is invalid', () => {
    useWordBankStore.getState().dispatchFilter({ type: 'edit', text: 'night' });
    useWordBankStore.getState().dispatchFilter({ type: 'edit', text: 'night &' });
    expect(useWordBankStore.getState().filter.error).not.toBeNull();
    expect(selectVisibleWords(useWordBankStore.getState()).map((w) => w.text)).toEqual(['night']);
  });

  it('prunes the selection to the words still shown', () => {
    useWordBankStore.getState().select({ type: 'set', ids: ['a', 'b', 'c'] });
    useWordBankStore.getState().dispatchFilter({ type: 'edit', text: 'night' });
    expect(selected()).toEqual(['b']);
  });

  it('survives throwing storage', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('quota');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    });
    useWordBankStore.getState().dispatchFilter({ type: 'edit', text: 'love' });
    expect(useWordBankStore.getState().filter.appliedText).toBe('love');
  });
});

describe('select', () => {
  it('cannot select filtered-out words', () => {
    useWordBankStore.getState().dispatchFilter({ type: 'edit', text: 'love' });
    useWordBankStore.getState().select({ type: 'add', ids: ['a', 'b'] });
    expect(selected()).toEqual(['b']);
  });
});

describe('addWords', () => {
  it('rejects an invalid batch locally with the first bad index', async () => {
    const r = await useWordBankStore.getState().addWords(['ok', 'bad1', 'x!'], []);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.detail).toEqual({ kind: 'invalid_word', index: 1 });
    expect(api.add).not.toHaveBeenCalled();
  });

  it('posts normalized words with tag ids and merges created and existing words', async () => {
    api.add.mockResolvedValueOnce({ created: [bw('n', 'star', 9, [love])], existing: [bw('a', 'tune', 1, [love])] });
    const r = await useWordBankStore.getState().addWords(['  star ', 'Tune'], ['t1']);
    expect(api.add).toHaveBeenCalledWith({ words: ['star', 'Tune'], tag_ids: ['t1'] });
    expect(r.ok).toBe(true);
    expect(words().map((w) => w.text)).toEqual(['star', 'moon', 'night', 'tune']);
    expect(words().find((w) => w.id === 'a')?.tags).toEqual([love]);
    // star is new with love, tune gained love: +2
    expect(wordCount('t1')).toBe(4);
    expect(useWordBankStore.getState().revision).toBe(1);
  });

  it('returns the server error typed', async () => {
    api.add.mockRejectedValueOnce(new WordBankApiError({ kind: 'invalid_word', index: 0 }, 'add_words', 'server says no', 422));
    const r = await useWordBankStore.getState().addWords(['fine'], []);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error.kind).toBe('invalid_word');
  });
});

describe('addFromText', () => {
  it('splits on commas and newlines and posts a summary notice', async () => {
    api.add.mockResolvedValueOnce({ created: [bw('n', 'star', 9)], existing: [bw('a', 'tune', 1)] });
    await useWordBankStore.getState().addFromText(' star, neon lights\ntune ', 'editor');
    expect(api.add).toHaveBeenCalledWith({ words: ['star', 'neon lights', 'tune'], tag_ids: [] });
    expect(useWordBankStore.getState().notice).toMatchObject({ tone: 'info', text: 'Added 1, 1 already in bank' });
  });

  it('posts an error notice naming the bad word', async () => {
    await useWordBankStore.getState().addFromText('good, b4d', 'editor');
    const notice = useWordBankStore.getState().notice;
    expect(notice?.tone).toBe('error');
    expect(notice?.text).toContain('"b4d"');
  });

  it('dismisses only the current notice', async () => {
    await useWordBankStore.getState().addFromText('', 'box');
    const id = useWordBankStore.getState().notice!.id;
    useWordBankStore.getState().dismissNotice(id + 1);
    expect(useWordBankStore.getState().notice).not.toBeNull();
    useWordBankStore.getState().dismissNotice(id);
    expect(useWordBankStore.getState().notice).toBeNull();
  });
});

describe('renameWord', () => {
  it('validates locally', async () => {
    const r = await useWordBankStore.getState().renameWord('a', 'tune2');
    expect(r.ok).toBe(false);
    expect(api.rename).not.toHaveBeenCalled();
  });

  it('skips an unchanged text', async () => {
    const r = await useWordBankStore.getState().renameWord('a', ' tune ');
    expect(r.ok).toBe(true);
    expect(api.rename).not.toHaveBeenCalled();
  });

  it('allows a change of case', async () => {
    api.rename.mockResolvedValueOnce(bw('a', 'Tune', 1));
    const r = await useWordBankStore.getState().renameWord('a', 'Tune');
    expect(api.rename).toHaveBeenCalledWith('a', 'Tune');
    expect(r.ok).toBe(true);
    expect(words().find((w) => w.id === 'a')?.text).toBe('Tune');
  });

  it('keeps word_exists typed and drops a word_not_found word', async () => {
    api.rename.mockRejectedValueOnce(new WordBankApiError({ kind: 'word_exists' }, 'rename_word', 'taken', 409));
    const r1 = await useWordBankStore.getState().renameWord('a', 'moon');
    expect(!r1.ok && r1.error.kind).toBe('word_exists');
    expect(words()).toHaveLength(3);
    api.rename.mockRejectedValueOnce(new WordBankApiError({ kind: 'word_not_found' }, 'rename_word', 'gone', 404));
    await useWordBankStore.getState().renameWord('a', 'other');
    expect(words().map((w) => w.id)).toEqual(['c', 'b']);
  });
});

describe('deleteWords', () => {
  it('removes the words, prunes the selection and lowers word counts', async () => {
    api.delete.mockResolvedValueOnce(undefined);
    useWordBankStore.getState().select({ type: 'set', ids: ['b', 'c'] });
    const r = await useWordBankStore.getState().deleteWords(['b', 'c']);
    expect(r.ok).toBe(true);
    expect(api.delete).toHaveBeenCalledWith(['b', 'c']);
    expect(words().map((w) => w.id)).toEqual(['a']);
    expect(selected()).toEqual([]);
    expect(wordCount('t1')).toBe(0);
    expect(wordCount('t2')).toBe(0);
  });

  it('keeps everything on failure', async () => {
    api.delete.mockRejectedValueOnce(new WordBankApiError({ kind: 'server' }, 'delete_words', 'x', 500));
    const r = await useWordBankStore.getState().deleteWords(['a']);
    expect(r.ok).toBe(false);
    expect(words()).toHaveLength(3);
  });
});

describe('tags on words', () => {
  it('links the selection where missing (mixed state) through POST /tags', async () => {
    useWordBankStore.getState().select({ type: 'set', ids: ['a', 'b'] });
    api.setTag.mockResolvedValueOnce({ words: [bw('a', 'tune', 1, [night])] });
    const r = await useWordBankStore.getState().toggleTagOnSelection('t2');
    expect(r.ok).toBe(true);
    expect(api.setTag).toHaveBeenCalledWith(['a'], 't2', 'link');
    expect(words().find((w) => w.id === 'a')?.tags).toEqual([night]);
    expect(wordCount('t2')).toBe(2);
  });

  it('unlinks from every selected word when all have it', async () => {
    useWordBankStore.getState().select({ type: 'set', ids: ['b', 'c'] });
    api.setTag.mockResolvedValueOnce({ words: [bw('c', 'moon', 3), bw('b', 'night', 2, [night])] });
    await useWordBankStore.getState().toggleTagOnSelection('t1');
    expect(api.setTag).toHaveBeenCalledWith(['c', 'b'], 't1', 'unlink');
    expect(wordCount('t1')).toBe(0);
  });

  it('changes nothing on word_not_found', async () => {
    api.setTag.mockRejectedValueOnce(new WordBankApiError({ kind: 'word_not_found' }, 'link_words', 'x', 404));
    const r = await useWordBankStore.getState().setTag(['a'], 't1', 'link');
    expect(!r.ok && r.error.kind).toBe('word_not_found');
    expect(words().find((w) => w.id === 'a')?.tags).toEqual([]);
  });

  it('forgets a tag the server no longer has', async () => {
    api.setTag.mockRejectedValueOnce(new WordBankApiError({ kind: 'tag_not_found' }, 'link_words', 'x', 404));
    await useWordBankStore.getState().setTag(['a'], 't2', 'link');
    expect(useTagStore.getState().tags.map((t) => t.id)).toEqual(['t1']);
    expect(words().find((w) => w.id === 'b')?.tags).toEqual([love]);
  });
});

describe('kept in step with tagStore', () => {
  it('a rename patches word pills and the word bank filter', async () => {
    useWordBankStore.getState().dispatchFilter({ type: 'edit', text: 'love' });
    tags.update.mockResolvedValueOnce({ data: T('t1', 'romance', { word_count: 2 }) } as never);
    vi.mocked((await import('@/api/client')).tagPresetApi.list).mockResolvedValueOnce({ data: [] } as never);
    const r = await useTagStore.getState().updateTag('t1', { name: 'romance' });
    expect(r.ok).toBe(true);
    expect(words().find((w) => w.id === 'b')?.tags.map((t) => t.name)).toEqual(['night', 'romance']);
    expect(useWordBankStore.getState().filter.text).toBe('romance');
    expect(selectVisibleWords(useWordBankStore.getState()).map((w) => w.id)).toEqual(['c', 'b']);
  });

  it('a delete removes the tag from words and the add box chips', async () => {
    useWordBankStore.getState().setAddTagIds(['t1', 't2']);
    tags.delete.mockResolvedValueOnce({ data: undefined } as never);
    await useTagStore.getState().deleteTag('t1');
    expect(words().map((w) => w.tags.map((t) => t.id))).toEqual([[], ['t2'], []]);
    expect(useWordBankStore.getState().addTagIds).toEqual(['t2']);
  });
});

describe('makeSnippet', () => {
  it('creates the draft at the viewport centre with its type and selects it', async () => {
    useCanvasStore.setState({ viewport: { x: -100, y: -50, zoom: 2 }, canvasViewSize: { width: 800, height: 600 } });
    createSnippet.mockResolvedValueOnce({ data: snippet('s9', 'moon\nnight') } as never);
    const r = await useWordBankStore.getState().makeSnippet({ text: 'moon\nnight', type: 'hook' });
    expect(r.ok).toBe(true);
    const [canvasId, body] = createSnippet.mock.calls[0];
    expect(canvasId).toBe('c1');
    expect(body).toMatchObject({ raw_text: 'moon\nnight', snippet_type: 'hook' });
    // viewport centre in canvas coordinates: (400/2 + 100, 300/2 + 50)
    expect({ x: body.position_x, y: body.position_y }).toEqual({ x: 300, y: 200 });
    expect([...useCanvasStore.getState().selectedSnippetIds]).toEqual(['s9']);
    expect(words()).toHaveLength(3);
  });

  it('uses the origin when the canvas was never measured', async () => {
    createSnippet.mockResolvedValueOnce({ data: snippet('s9', 'x') } as never);
    await useWordBankStore.getState().makeSnippet({ text: 'x', type: 'freestyle' });
    expect(createSnippet.mock.calls[0][1]).toMatchObject({ position_x: 0, position_y: 0 });
  });

  it('rejects an empty draft and maps request failures', async () => {
    expect((await useWordBankStore.getState().makeSnippet({ text: '  ', type: 'freestyle' })).ok).toBe(false);
    expect(createSnippet).not.toHaveBeenCalled();
    createSnippet.mockRejectedValueOnce(new Error('boom'));
    const r = await useWordBankStore.getState().makeSnippet({ text: 'x', type: 'freestyle' });
    expect(!r.ok && r.error.operation).toBe('make_snippet');
  });
});
