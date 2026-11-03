import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { tagApi, tagPresetApi } from '@/api/client';
import { useTagStore } from './tagStore';
import { useCanvasStore } from './canvasStore';
import { useTagFilterStore } from './tagFilterStore';
import { useRhymeStore } from './rhymeStore';
import { snippet, tag } from '@/lib/merge/fixtures';
import { EMPTY_FILTER, filterReducer } from '@/lib/tagFilter';
import type { Tag, TagPreset } from '@/types';

vi.mock('@/api/client', () => ({
  tagApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn(), addToSnippet: vi.fn(), removeFromSnippet: vi.fn() },
  tagPresetApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
  nlpApi: { rhymeSuggest: vi.fn() },
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
}));

const api = vi.mocked(tagApi);
const presetApi = vi.mocked(tagPresetApi);

const T = (id: string, name: string, extra: Partial<Tag> = {}): Tag => ({
  id, name, color: '#111111', snippet_count: 0, created_at: '2026-11-03T00:00:00Z', ...extra,
});
const P = (id: string, name: string, expression: string): TagPreset => ({
  id, name, expression, created_at: '', updated_at: '',
});
const ok = <T,>(data: T) => ({ data }) as never;

function axiosErr(status: number, detail: unknown): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError('fail', 'E', config, undefined, { status, statusText: '', headers: {}, config, data: { detail } });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  useTagStore.setState({ tags: [T('t1', 'love', { snippet_count: 2 }), T('t2', 'night')], presets: [], loadState: 'ready', loadError: null });
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map([
      ['a', snippet('a', 'x', { tags: [tag('t1', 'love')] })],
      ['b', snippet('b', 'y', { tags: [tag('t1', 'love')] })],
      ['c', snippet('c', 'z')],
    ]),
    past: [],
    future: [],
  });
  useTagFilterStore.setState({ canvasId: null, filter: EMPTY_FILTER });
  useRhymeStore.setState({ tagFilterText: '' });
});

describe('load', () => {
  it('loads tags and presets sorted by lower(name)', async () => {
    api.list.mockResolvedValueOnce(ok([T('1', 'b'), T('2', 'A')]));
    presetApi.list.mockResolvedValueOnce(ok([P('p2', 'zeta', 'a'), P('p1', 'Alpha', 'b')]));
    const r = await useTagStore.getState().load();
    expect(r.ok).toBe(true);
    const s = useTagStore.getState();
    expect(s.tags.map((t) => t.name)).toEqual(['A', 'b']);
    expect(s.presets.map((p) => p.name)).toEqual(['Alpha', 'zeta']);
    expect(s.loadState).toBe('ready');
  });

  it('records a typed failure', async () => {
    api.list.mockRejectedValueOnce(axiosErr(500, 'boom'));
    presetApi.list.mockResolvedValueOnce(ok([]));
    const r = await useTagStore.getState().load();
    expect(r).toMatchObject({ ok: false, error: { kind: 'server', operation: 'load_tags' } });
    expect(useTagStore.getState().loadState).toBe('failed');
  });
});

describe('createTag', () => {
  it('posts the trimmed name and inserts in order', async () => {
    api.create.mockResolvedValueOnce(ok(T('t3', 'dark')));
    const r = await useTagStore.getState().createTag('  dark ', '#ff0000');
    expect(api.create).toHaveBeenCalledWith('dark', '#ff0000');
    expect(r.ok).toBe(true);
    expect(useTagStore.getState().tags.map((t) => t.name)).toEqual(['dark', 'love', 'night']);
  });

  it('maps 409 tag_name_taken', async () => {
    api.create.mockRejectedValueOnce(axiosErr(409, { error: 'tag_name_taken', message: 'taken' }));
    const r = await useTagStore.getState().createTag('Love');
    expect(r).toMatchObject({ ok: false, error: { kind: 'tag_name_taken', status: 409 } });
  });

  it('rejects bad names and colors locally', async () => {
    expect(await useTagStore.getState().createTag('   ')).toMatchObject({ ok: false, error: { kind: 'invalid_tag_name' } });
    expect(await useTagStore.getState().createTag('x', 'red')).toMatchObject({ ok: false, error: { kind: 'invalid_color' } });
    expect(api.create).not.toHaveBeenCalled();
  });
});

describe('updateTag', () => {
  it('renames: patches embedded tags (live and history), refetches presets and follows the open filters', async () => {
    useCanvasStore.setState({ past: [{ snippets: new Map(useCanvasStore.getState().snippets), groups: new Map() }] });
    useTagFilterStore.setState({ canvasId: null, filter: filterReducer(EMPTY_FILTER, { type: 'edit', text: 'love & night' }) });
    useRhymeStore.setState({ tagFilterText: '!love' });
    api.update.mockResolvedValueOnce(ok(T('t1', 'romance', { snippet_count: 2, color: '#222222' })));
    presetApi.list.mockResolvedValueOnce(ok([P('p1', 'mine', 'romance')]));

    const r = await useTagStore.getState().updateTag('t1', { name: ' romance ' });

    expect(api.update).toHaveBeenCalledWith('t1', { name: 'romance' });
    expect(r.ok).toBe(true);
    const canvas = useCanvasStore.getState();
    expect(canvas.snippets.get('a')?.tags).toEqual([{ id: 't1', name: 'romance', color: '#222222' }]);
    expect(canvas.past[0].snippets.get('b')?.tags[0].name).toBe('romance');
    expect(useTagStore.getState().tags.map((t) => t.name)).toEqual(['night', 'romance']);
    expect(presetApi.list).toHaveBeenCalledTimes(1);
    expect(useTagStore.getState().presets[0].expression).toBe('romance');
    expect(useTagFilterStore.getState().filter.text).toBe('romance & night');
    expect(useRhymeStore.getState().tagFilterText).toBe('!romance');
  });

  it('recolors without refetching presets', async () => {
    api.update.mockResolvedValueOnce(ok(T('t1', 'love', { color: '#00ff00' })));
    await useTagStore.getState().updateTag('t1', { color: '#00ff00' });
    expect(presetApi.list).not.toHaveBeenCalled();
    expect(useCanvasStore.getState().snippets.get('a')?.tags[0].color).toBe('#00ff00');
  });

  it('drops a tag the server no longer has', async () => {
    api.update.mockRejectedValueOnce(axiosErr(404, { error: 'tag_not_found', message: 'gone' }));
    const r = await useTagStore.getState().updateTag('t1', { name: 'x' });
    expect(r).toMatchObject({ ok: false, error: { kind: 'tag_not_found' } });
    expect(useTagStore.getState().tags.map((t) => t.id)).toEqual(['t2']);
    expect(useCanvasStore.getState().snippets.get('a')?.tags).toEqual([]);
  });
});

describe('deleteTag', () => {
  it('removes the tag from the list and from snippets', async () => {
    api.delete.mockResolvedValueOnce(ok(undefined));
    expect((await useTagStore.getState().deleteTag('t1')).ok).toBe(true);
    expect(useTagStore.getState().tags.map((t) => t.id)).toEqual(['t2']);
    expect(useCanvasStore.getState().snippets.get('b')?.tags).toEqual([]);
  });

  it('treats tag_not_found as already deleted', async () => {
    api.delete.mockRejectedValueOnce(axiosErr(404, { error: 'tag_not_found', message: 'gone' }));
    expect((await useTagStore.getState().deleteTag('t1')).ok).toBe(true);
    expect(useTagStore.getState().tags.map((t) => t.id)).toEqual(['t2']);
  });

  it('keeps everything on other failures', async () => {
    api.delete.mockRejectedValueOnce(axiosErr(500, 'x'));
    expect((await useTagStore.getState().deleteTag('t1')).ok).toBe(false);
    expect(useTagStore.getState().tags).toHaveLength(2);
  });
});

describe('applyToggle', () => {
  it('links on every planned snippet and bumps the count', async () => {
    api.addToSnippet.mockResolvedValue(ok({ ok: true }));
    const r = await useTagStore.getState().applyToggle({ action: 'link', tagId: 't2', snippetIds: ['a', 'c'] });
    expect(r.ok).toBe(true);
    expect(api.addToSnippet.mock.calls).toEqual([['a', 't2'], ['c', 't2']]);
    const snippets = useCanvasStore.getState().snippets;
    expect(snippets.get('a')?.tags.map((t) => t.id)).toEqual(['t1', 't2']);
    expect(snippets.get('c')?.tags).toEqual([{ id: 't2', name: 'night', color: '#111111' }]);
    expect(useTagStore.getState().tags.find((t) => t.id === 't2')?.snippet_count).toBe(2);
  });

  it('unlinks and lowers the count', async () => {
    api.removeFromSnippet.mockResolvedValue(ok(undefined));
    await useTagStore.getState().applyToggle({ action: 'unlink', tagId: 't1', snippetIds: ['a', 'b'] });
    expect(useCanvasStore.getState().snippets.get('a')?.tags).toEqual([]);
    expect(useTagStore.getState().tags.find((t) => t.id === 't1')?.snippet_count).toBe(0);
  });

  it('keeps the links that succeeded and reports the first failure', async () => {
    api.addToSnippet.mockResolvedValueOnce(ok({ ok: true })).mockRejectedValueOnce(axiosErr(404, 'Not found'));
    const r = await useTagStore.getState().applyToggle({ action: 'link', tagId: 't2', snippetIds: ['a', 'c'] });
    expect(r).toMatchObject({ ok: false, error: { kind: 'not_found', operation: 'link_tag' } });
    expect(useCanvasStore.getState().snippets.get('a')?.tags.map((t) => t.id)).toEqual(['t1', 't2']);
    expect(useCanvasStore.getState().snippets.get('c')?.tags).toEqual([]);
    expect(useTagStore.getState().tags.find((t) => t.id === 't2')?.snippet_count).toBe(1);
  });

  it('does nothing for an unknown tag', async () => {
    const r = await useTagStore.getState().applyToggle({ action: 'link', tagId: 'zz', snippetIds: ['a'] });
    expect(r).toMatchObject({ ok: false, error: { kind: 'tag_not_found' } });
    expect(api.addToSnippet).not.toHaveBeenCalled();
  });
});

describe('presets', () => {
  it('creates with the normalized expression', async () => {
    presetApi.create.mockResolvedValueOnce(ok(P('p1', 'mine', 'love & !draft')));
    const r = await useTagStore.getState().createPreset(' mine ', 'love and not draft');
    expect(presetApi.create).toHaveBeenCalledWith('mine', 'love & !draft');
    expect(r.ok).toBe(true);
    expect(useTagStore.getState().presets.map((p) => p.id)).toEqual(['p1']);
  });

  it('blocks empty and invalid expressions locally', async () => {
    expect(await useTagStore.getState().createPreset('x', '  ')).toMatchObject({ ok: false, error: { kind: 'empty_preset' } });
    expect(await useTagStore.getState().createPreset('x', 'love &')).toMatchObject({
      ok: false,
      error: { kind: 'invalid_tag_expression', expressionError: { code: 'unexpected_end', position: 6 } },
    });
    expect(await useTagStore.getState().createPreset(' ', 'love')).toMatchObject({ ok: false, error: { kind: 'invalid_preset_name' } });
    expect(presetApi.create).not.toHaveBeenCalled();
  });

  it('maps 409 preset_name_taken', async () => {
    presetApi.create.mockRejectedValueOnce(axiosErr(409, { error: 'preset_name_taken', message: 'taken' }));
    expect(await useTagStore.getState().createPreset('mine', 'love')).toMatchObject({ ok: false, error: { kind: 'preset_name_taken' } });
  });

  it('renames / overwrites and keeps order', async () => {
    useTagStore.setState({ presets: [P('p1', 'a', 'x'), P('p2', 'b', 'y')] });
    presetApi.update.mockResolvedValueOnce(ok(P('p1', 'c', 'x')));
    await useTagStore.getState().updatePreset('p1', { name: 'c' });
    expect(useTagStore.getState().presets.map((p) => p.name)).toEqual(['b', 'c']);
    presetApi.update.mockResolvedValueOnce(ok(P('p2', 'b', 'love & night')));
    await useTagStore.getState().updatePreset('p2', { expression: 'love night' });
    expect(presetApi.update).toHaveBeenLastCalledWith('p2', { expression: 'love & night' });
  });

  it('deletes, treating preset_not_found as done', async () => {
    useTagStore.setState({ presets: [P('p1', 'a', 'x'), P('p2', 'b', 'y')] });
    presetApi.delete.mockResolvedValueOnce(ok(undefined));
    await useTagStore.getState().deletePreset('p1');
    presetApi.delete.mockRejectedValueOnce(axiosErr(404, { error: 'preset_not_found', message: 'gone' }));
    expect((await useTagStore.getState().deletePreset('p2')).ok).toBe(true);
    expect(useTagStore.getState().presets).toEqual([]);
  });
});
