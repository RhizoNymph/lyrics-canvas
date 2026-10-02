import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { canvasApi } from '@/api/client';
import { useCanvasStore } from './canvasStore';
import { snippet, tag } from '@/lib/merge/fixtures';
import type { MergeResponse } from '@/types';

vi.mock('@/api/client', () => ({
  nlpApi: {},
  snippetApi: {},
  canvasApi: { merge: vi.fn() },
  groupApi: {},
  tagApi: {},
}));

const merge = vi.mocked(canvasApi.merge);

function axiosErr(status: number | null, detail = 'nope'): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError(
    'fail',
    'E',
    config,
    undefined,
    status === null ? undefined : { status, statusText: '', headers: {}, config, data: { detail } }
  );
}

function respond(body: MergeResponse) {
  merge.mockResolvedValueOnce({ data: body } as never);
}

const top = snippet('top', 'one two', { position_x: 100, position_y: 100, snippet_type: 'chorus', tags: [tag('t1')] });
const bottom = snippet('bottom', 'three', { position_x: 50, position_y: 300, tags: [tag('t1'), tag('t2')] });
const right = snippet('right', 'four five', { position_x: 400, position_y: 100 });
const other = snippet('other', 'untouched', { position_x: 900, position_y: 900 });
const merged = snippet('m', 'one two\nthree', { position_x: 100, position_y: 100, snippet_type: 'chorus', tags: [tag('t1'), tag('t2')] });

beforeEach(() => {
  vi.clearAllMocks();
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map([top, bottom, right, other].map((s) => [s.id, s])),
    tags: new Map([tag('t1'), tag('t2')].map((t) => [t.id, t])),
    selectedSnippetIds: new Set(['bottom', 'top']),
    past: [],
    future: [],
    mergeError: null,
  });
});

describe('mergeSelected: guards', () => {
  it('fails with no_canvas when no canvas is loaded', async () => {
    useCanvasStore.setState({ canvasId: null });
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result).toMatchObject({ ok: false, error: { kind: 'no_canvas' } });
    expect(merge).not.toHaveBeenCalled();
  });

  it('fails with too_few_selected below two snippets', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(['top']) });
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result).toMatchObject({ ok: false, error: { kind: 'too_few_selected', ids: ['top'] } });
    expect(merge).not.toHaveBeenCalled();
    expect(useCanvasStore.getState().mergeError?.kind).toBe('too_few_selected');
  });

  it('ignores selected ids that no longer exist', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(['top', 'gone']) });
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result).toMatchObject({ ok: false, error: { kind: 'too_few_selected' } });
    expect(merge).not.toHaveBeenCalled();
  });
});

describe('mergeSelected: success', () => {
  it('posts the selected ids and mode, replaces the originals with the merged snippet and selects it', async () => {
    respond({ snippet: merged, deleted_ids: ['top', 'bottom'] });
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result).toEqual({ ok: true, snippet: merged });

    expect(merge).toHaveBeenCalledTimes(1);
    const [canvasId, ids, mode] = merge.mock.calls[0];
    expect(canvasId).toBe('c1');
    expect([...ids].sort()).toEqual(['bottom', 'top']);
    expect(mode).toBe('vertical');

    const state = useCanvasStore.getState();
    expect([...state.snippets.keys()].sort()).toEqual(['m', 'other', 'right']);
    expect(state.snippets.get('m')).toEqual(merged);
    expect(state.selectedSnippetIds).toEqual(new Set(['m']));
    expect(state.mergeError).toBeNull();
  });

  it('keeps the tags the server returned on the merged snippet', async () => {
    respond({ snippet: merged, deleted_ids: ['top', 'bottom'] });
    await useCanvasStore.getState().mergeSelected('vertical');
    expect(useCanvasStore.getState().snippets.get('m')?.tags.map((t) => t.id)).toEqual(['t1', 't2']);
  });

  it('passes the horizontal mode through', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(['right', 'top']) });
    respond({ snippet: snippet('h', 'one two four five'), deleted_ids: ['top', 'right'] });
    const result = await useCanvasStore.getState().mergeSelected('horizontal');
    expect(result.ok).toBe(true);
    expect(merge.mock.calls[0][2]).toBe('horizontal');
    expect([...useCanvasStore.getState().snippets.keys()].sort()).toEqual(['bottom', 'h', 'other']);
  });

  it('removes exactly the deleted ids the server reports', async () => {
    respond({ snippet: merged, deleted_ids: ['top'] });
    await useCanvasStore.getState().mergeSelected('vertical');
    expect([...useCanvasStore.getState().snippets.keys()].sort()).toEqual(['bottom', 'm', 'other', 'right']);
  });

  it('pushes exactly one undo state, from before the merge', async () => {
    respond({ snippet: merged, deleted_ids: ['top', 'bottom'] });
    await useCanvasStore.getState().mergeSelected('vertical');
    const { past } = useCanvasStore.getState();
    expect(past).toHaveLength(1);
    expect([...past[0].snippets.keys()].sort()).toEqual(['bottom', 'other', 'right', 'top']);
    useCanvasStore.getState().undo();
    expect([...useCanvasStore.getState().snippets.keys()].sort()).toEqual(['bottom', 'other', 'right', 'top']);
  });

  it('clears a previous merge error', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(['top']) });
    await useCanvasStore.getState().mergeSelected('vertical');
    useCanvasStore.setState({ selectedSnippetIds: new Set(['top', 'bottom']) });
    respond({ snippet: merged, deleted_ids: ['top', 'bottom'] });
    await useCanvasStore.getState().mergeSelected('vertical');
    expect(useCanvasStore.getState().mergeError).toBeNull();
  });
});

describe('mergeSelected: failures', () => {
  it('a rejected request changes nothing and returns merge_failed with status and detail', async () => {
    merge.mockRejectedValueOnce(axiosErr(404, 'Snippet not found'));
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result).toMatchObject({ ok: false, error: { kind: 'merge_failed', status: 404, message: 'Snippet not found' } });
    if (result.ok) return;
    expect([...result.error.ids].sort()).toEqual(['bottom', 'top']);
    const state = useCanvasStore.getState();
    expect([...state.snippets.keys()].sort()).toEqual(['bottom', 'other', 'right', 'top']);
    expect(state.selectedSnippetIds).toEqual(new Set(['bottom', 'top']));
    expect(state.past).toHaveLength(0);
    expect(state.mergeError?.kind).toBe('merge_failed');
  });

  it('a network error has a null status', async () => {
    merge.mockRejectedValueOnce(axiosErr(null));
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result).toMatchObject({ ok: false, error: { kind: 'merge_failed', status: null, message: 'fail' } });
  });

  it('never throws, whatever the client rejects with', async () => {
    merge.mockRejectedValueOnce('boom');
    await expect(useCanvasStore.getState().mergeSelected('vertical')).resolves.toMatchObject({
      ok: false,
      error: { kind: 'merge_failed', message: 'boom' },
    });
  });

  it('clearMergeError resets the surfaced error', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(['top']) });
    await useCanvasStore.getState().mergeSelected('vertical');
    expect(useCanvasStore.getState().mergeError).not.toBeNull();
    useCanvasStore.getState().clearMergeError();
    expect(useCanvasStore.getState().mergeError).toBeNull();
  });
});
