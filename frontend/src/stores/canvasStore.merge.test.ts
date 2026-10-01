import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import { snippetApi, tagApi } from '@/api/client';
import { useCanvasStore } from './canvasStore';
import { snippet, tag } from '@/lib/merge/fixtures';
import type { Snippet } from '@/types';

vi.mock('@/api/client', () => ({
  nlpApi: {},
  snippetApi: { create: vi.fn(), delete: vi.fn() },
  canvasApi: {},
  groupApi: {},
  tagApi: { addToSnippet: vi.fn() },
}));

const createSnippet = vi.mocked(snippetApi.create);
const deleteSnippet = vi.mocked(snippetApi.delete);
const addToSnippet = vi.mocked(tagApi.addToSnippet);

function axiosErr(status: number | null): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError(
    'fail',
    'E',
    config,
    undefined,
    status === null ? undefined : { status, statusText: '', headers: {}, config, data: { detail: 'nope' } }
  );
}

/** The server echoes the body back as the created snippet. */
function echoCreate(id = 'm') {
  createSnippet.mockImplementation(async (_canvasId, body) => ({
    data: { ...snippet(id, body.raw_text ?? ''), ...body, grid_data: body.grid_data ?? null, tags: [] } as Snippet,
  }) as never);
}

const top = snippet('top', 'one two', { position_x: 100, position_y: 100, snippet_type: 'chorus', tags: [tag('t1')] });
const bottom = snippet('bottom', 'three', { position_x: 50, position_y: 300, tags: [tag('t1'), tag('t2')] });
const right = snippet('right', 'four five', { position_x: 400, position_y: 100 });
const other = snippet('other', 'untouched', { position_x: 900, position_y: 900 });

beforeEach(() => {
  vi.clearAllMocks();
  deleteSnippet.mockResolvedValue({} as never);
  addToSnippet.mockResolvedValue({} as never);
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
    expect(result).toMatchObject({ ok: false, error: { kind: 'no_canvas', level: 'error' } });
    expect(createSnippet).not.toHaveBeenCalled();
  });

  it('fails with too_few_selected below two snippets', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(['top']) });
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result).toMatchObject({ ok: false, error: { kind: 'too_few_selected' } });
    expect(createSnippet).not.toHaveBeenCalled();
    expect(useCanvasStore.getState().mergeError?.kind).toBe('too_few_selected');
  });

  it('ignores selected ids that no longer exist', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(['top', 'gone']) });
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result).toMatchObject({ ok: false, error: { kind: 'too_few_selected' } });
  });
});

describe('mergeSelected: vertical', () => {
  it('orders by position, posts the merged body at the first block, deletes the originals and selects the result', async () => {
    echoCreate();
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result.ok).toBe(true);

    expect(createSnippet).toHaveBeenCalledTimes(1);
    const [canvasId, body] = createSnippet.mock.calls[0];
    expect(canvasId).toBe('c1');
    expect(body).toMatchObject({
      raw_text: 'one two\nthree',
      snippet_type: 'chorus',
      grid_mode: 'auto',
      beats_per_line: null,
      position_x: 100,
      position_y: 100,
      group_id: null,
    });
    expect(body.grid_data?.lines.map((l) => l.raw_text)).toEqual(['one two', 'three']);
    expect(body.grid_data?.lines[0].cells.map((c) => c.syllable)).toEqual(['one', 'two']);

    // originals deleted only after creation
    expect(deleteSnippet.mock.calls.map((c) => c[0]).sort()).toEqual(['bottom', 'top']);
    expect(deleteSnippet.mock.invocationCallOrder[0]).toBeGreaterThan(createSnippet.mock.invocationCallOrder[0]);

    const state = useCanvasStore.getState();
    expect([...state.snippets.keys()].sort()).toEqual(['m', 'other', 'right']);
    expect(state.selectedSnippetIds).toEqual(new Set(['m']));
    expect(state.mergeError).toBeNull();
  });

  it('re-attaches the union of the originals tags and reflects them locally', async () => {
    echoCreate();
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result.ok).toBe(true);
    expect(addToSnippet.mock.calls).toEqual([
      ['m', 't1'],
      ['m', 't2'],
    ]);
    expect(useCanvasStore.getState().snippets.get('m')?.tags.map((t) => t.id)).toEqual(['t1', 't2']);
  });

  it('pushes exactly one undo state, from before the merge', async () => {
    echoCreate();
    await useCanvasStore.getState().mergeSelected('vertical');
    const { past } = useCanvasStore.getState();
    expect(past).toHaveLength(1);
    expect([...past[0].snippets.keys()].sort()).toEqual(['bottom', 'other', 'right', 'top']);
    useCanvasStore.getState().undo();
    expect([...useCanvasStore.getState().snippets.keys()].sort()).toEqual(['bottom', 'other', 'right', 'top']);
  });
});

describe('mergeSelected: horizontal', () => {
  it('orders left-to-right and joins lines', async () => {
    echoCreate();
    useCanvasStore.setState({ selectedSnippetIds: new Set(['right', 'top']) });
    const result = await useCanvasStore.getState().mergeSelected('horizontal');
    expect(result.ok).toBe(true);
    const body = createSnippet.mock.calls[0][1];
    expect(body.raw_text).toBe('one two four five');
    expect(body.position_x).toBe(100);
    expect(body.grid_data?.lines[0].cells.map((c) => c.word_index)).toEqual([0, 1, 2, 3]);
    expect(deleteSnippet.mock.calls.map((c) => c[0]).sort()).toEqual(['right', 'top']);
  });
});

describe('mergeSelected: failures', () => {
  it('create failure changes nothing and returns an error-level result', async () => {
    createSnippet.mockRejectedValueOnce(axiosErr(500));
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result).toMatchObject({ ok: false, error: { kind: 'create_failed', level: 'error', status: 500, message: 'nope' } });
    expect(deleteSnippet).not.toHaveBeenCalled();
    const state = useCanvasStore.getState();
    expect([...state.snippets.keys()].sort()).toEqual(['bottom', 'other', 'right', 'top']);
    expect(state.selectedSnippetIds).toEqual(new Set(['bottom', 'top']));
    expect(state.past).toHaveLength(0);
    expect(state.mergeError?.kind).toBe('create_failed');
  });

  it('a failed delete keeps the merged snippet, removes the others, and surfaces a warning', async () => {
    echoCreate();
    deleteSnippet.mockImplementation(async (id) => {
      if (id === 'bottom') throw axiosErr(404);
      return {} as never;
    });
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.snippet.id).toBe('m');
    expect(result.warnings).toHaveLength(1);
    expect(result.warnings[0]).toMatchObject({ kind: 'delete_failed', level: 'warning', status: 404, ids: ['bottom'] });
    const state = useCanvasStore.getState();
    expect([...state.snippets.keys()].sort()).toEqual(['bottom', 'm', 'other', 'right']);
    expect(state.selectedSnippetIds).toEqual(new Set(['m']));
    expect(state.past).toHaveLength(1);
    expect(state.mergeError?.kind).toBe('delete_failed');
  });

  it('a failed tag attach is a warning; the merged snippet keeps the tags that did attach', async () => {
    echoCreate();
    addToSnippet.mockImplementation(async (_sid, tagId) => {
      if (tagId === 't1') throw axiosErr(500);
      return {} as never;
    });
    const result = await useCanvasStore.getState().mergeSelected('vertical');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.warnings.map((w) => [w.kind, [...w.ids]])).toEqual([['tag_failed', ['t1']]]);
    expect(useCanvasStore.getState().snippets.get('m')?.tags.map((t) => t.id)).toEqual(['t2']);
    expect(deleteSnippet).toHaveBeenCalledTimes(2);
  });

  it('clearMergeError resets the surfaced error', async () => {
    useCanvasStore.setState({ selectedSnippetIds: new Set(['top']) });
    await useCanvasStore.getState().mergeSelected('vertical');
    expect(useCanvasStore.getState().mergeError).not.toBeNull();
    useCanvasStore.getState().clearMergeError();
    expect(useCanvasStore.getState().mergeError).toBeNull();
  });
});
