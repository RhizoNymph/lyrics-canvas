import { describe, it, expect, vi, beforeEach } from 'vitest';
import { placementApi } from '@/api/client';
import { useCanvasStore } from './canvasStore';
import { snippet } from '@/lib/merge/fixtures';
import { PlacementError } from '@/lib/placements';
import type { Group, Snippet, TransferResponse } from '@/types';

vi.mock('@/api/client', () => ({
  nlpApi: {},
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
  placementApi: {
    renameCanvas: vi.fn(),
    updateSnippet: vi.fn(),
    batchPosition: vi.fn(),
    removeFromCanvas: vi.fn(),
    deleteEverywhere: vi.fn(),
    transfer: vi.fn(),
  },
}));

const api = vi.mocked(placementApi);

const here = { id: 'c1', title: 'Canvas' };
const album = { id: 'a', title: 'Album' };
const target = { id: 't', title: 'Target' };

const solo = snippet('solo', 'only here', { canvases: [here] });
const shared = snippet('shared', 'also on album', { canvases: [album, here] });
const other = snippet('other', 'untouched', { canvases: [here], position_x: 5, position_y: 6 });

function snap(...snippets: Snippet[]) {
  return { snippets: new Map(snippets.map((s) => [s.id, s])), groups: new Map<string, Group>() };
}

const group: Group = {
  id: 'g',
  canvas_id: 'c1',
  name: 'G',
  position_x: 0,
  position_y: 0,
  snippet_ids: ['solo', 'other'],
  created_at: '',
};

beforeEach(() => {
  vi.clearAllMocks();
  useCanvasStore.setState({
    canvasId: 'c1',
    canvasTitle: 'Canvas',
    snippets: new Map([solo, shared, other].map((s) => [s.id, s])),
    groups: new Map([[group.id, group]]),
    selectedSnippetIds: new Set(['solo', 'shared']),
    snippetScales: new Map([['solo', 2]]),
    past: [snap(other), snap(solo, other), snap(solo, shared, other)],
    future: [snap(shared, other)],
  });
});

const keys = () => [...useCanvasStore.getState().snippets.keys()].sort();

describe('updateSnippet', () => {
  it('sends the open canvas id and stores the response', async () => {
    const saved = { ...solo, raw_text: 'edited' };
    api.updateSnippet.mockResolvedValueOnce(saved);
    await useCanvasStore.getState().updateSnippet('solo', { raw_text: 'edited' });
    expect(api.updateSnippet).toHaveBeenCalledWith('solo', 'c1', { raw_text: 'edited' });
    expect(useCanvasStore.getState().snippets.get('solo')).toEqual(saved);
  });

  it('drops a response that arrives after another canvas was opened', async () => {
    let resolve: (s: Snippet) => void = () => undefined;
    api.updateSnippet.mockReturnValueOnce(new Promise<Snippet>((r) => { resolve = r; }));
    const pending = useCanvasStore.getState().updateSnippet('solo', { raw_text: 'late' });
    useCanvasStore.setState({ canvasId: 'c2', snippets: new Map(), past: [] });
    resolve({ ...solo, raw_text: 'late' });
    await pending;
    expect(useCanvasStore.getState().snippets.size).toBe(0);
    expect(useCanvasStore.getState().past).toEqual([]);
  });

  it('does nothing without a canvas', async () => {
    useCanvasStore.setState({ canvasId: null });
    await useCanvasStore.getState().updateSnippet('solo', { raw_text: 'x' });
    expect(api.updateSnippet).not.toHaveBeenCalled();
  });
});

describe('commitPositions', () => {
  it('sends canvas_id with every position of the canvas', async () => {
    api.batchPosition.mockResolvedValueOnce(undefined);
    await useCanvasStore.getState().commitPositions();
    expect(api.batchPosition).toHaveBeenCalledTimes(1);
    const [canvasId, updates] = api.batchPosition.mock.calls[0];
    expect(canvasId).toBe('c1');
    expect(updates).toContainEqual({ id: 'other', position_x: 5, position_y: 6 });
    expect(updates).toHaveLength(3);
  });

  it('skips the call without a canvas or without snippets', async () => {
    useCanvasStore.setState({ canvasId: null });
    await useCanvasStore.getState().commitPositions();
    useCanvasStore.setState({ canvasId: 'c1', snippets: new Map() });
    await useCanvasStore.getState().commitPositions();
    expect(api.batchPosition).not.toHaveBeenCalled();
  });
});

describe('removeFromCanvas', () => {
  it('removes each placement, reports which snippets were deleted for good, and cleans local state', async () => {
    api.removeFromCanvas.mockImplementation(async (_cid, sid) => ({ snippet_deleted: sid === 'solo' }));
    const summary = await useCanvasStore.getState().removeFromCanvas(['solo', 'shared']);
    expect(api.removeFromCanvas).toHaveBeenCalledWith('c1', 'solo');
    expect(api.removeFromCanvas).toHaveBeenCalledWith('c1', 'shared');
    expect(summary).toEqual({ removed: ['solo', 'shared'], deletedForGood: ['solo'], failures: [] });
    const state = useCanvasStore.getState();
    expect(keys()).toEqual(['other']);
    expect(state.selectedSnippetIds.size).toBe(0);
    expect(state.snippetScales.has('solo')).toBe(false);
    expect(state.groups.get('g')?.snippet_ids).toEqual(['other']);
  });

  it('drops every history entry that holds a removed snippet, so undo/redo cannot bring it back', async () => {
    api.removeFromCanvas.mockResolvedValue({ snippet_deleted: false });
    await useCanvasStore.getState().removeFromCanvas(['shared']);
    const { past, future } = useCanvasStore.getState();
    expect(past.map((s) => [...s.snippets.keys()].sort())).toEqual([['other'], ['other', 'solo']]);
    expect(future).toEqual([]);
    useCanvasStore.getState().undo();
    useCanvasStore.getState().undo();
    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().snippets.has('shared')).toBe(false);
    useCanvasStore.getState().redo();
    useCanvasStore.getState().redo();
    expect(useCanvasStore.getState().snippets.has('shared')).toBe(false);
  });

  it('treats placement_not_found as already removed', async () => {
    api.removeFromCanvas.mockRejectedValueOnce(new PlacementError('placement_not_found', 'remove_from_canvas', 'gone', 404));
    const summary = await useCanvasStore.getState().removeFromCanvas(['shared']);
    expect(summary).toEqual({ removed: ['shared'], deletedForGood: [], failures: [] });
    expect(keys()).toEqual(['other', 'solo']);
  });

  it('keeps a snippet whose removal failed and reports the typed failure', async () => {
    api.removeFromCanvas.mockImplementation(async (_cid, sid) => {
      if (sid === 'shared') throw new PlacementError('network', 'remove_from_canvas', 'offline');
      return { snippet_deleted: true };
    });
    const summary = await useCanvasStore.getState().removeFromCanvas(['solo', 'shared']);
    expect(summary.removed).toEqual(['solo']);
    expect(summary.failures).toHaveLength(1);
    expect(summary.failures[0].id).toBe('shared');
    expect(summary.failures[0].error.kind).toBe('network');
    expect(keys()).toEqual(['other', 'shared']);
    expect(useCanvasStore.getState().selectedSnippetIds).toEqual(new Set(['shared']));
  });

  it('ignores ids that are not on the canvas and does nothing without a canvas', async () => {
    const summary = await useCanvasStore.getState().removeFromCanvas(['nope']);
    expect(summary).toEqual({ removed: [], deletedForGood: [], failures: [] });
    useCanvasStore.setState({ canvasId: null });
    await useCanvasStore.getState().removeFromCanvas(['solo']);
    expect(api.removeFromCanvas).not.toHaveBeenCalled();
  });
});

describe('deleteEverywhere', () => {
  it('deletes each snippet and removes it with its history', async () => {
    api.deleteEverywhere.mockResolvedValue(undefined);
    const summary = await useCanvasStore.getState().deleteEverywhere(['shared']);
    expect(api.deleteEverywhere).toHaveBeenCalledWith('shared');
    expect(summary).toEqual({ removed: ['shared'], deletedForGood: ['shared'], failures: [] });
    expect(keys()).toEqual(['other', 'solo']);
    expect(useCanvasStore.getState().past.every((s) => !s.snippets.has('shared'))).toBe(true);
  });

  it('a 404 means it is already gone', async () => {
    api.deleteEverywhere.mockRejectedValueOnce(new PlacementError('not_found', 'delete_everywhere', 'Snippet not found', 404));
    const summary = await useCanvasStore.getState().deleteEverywhere(['shared']);
    expect(summary.removed).toEqual(['shared']);
    expect(summary.failures).toEqual([]);
  });
});

function transferResponse(overrides: Partial<TransferResponse>): TransferResponse {
  return { target_canvas_id: 't', placed: [], already_on_target: [], removed_from_source: false, ...overrides };
}

describe('transferSnippets', () => {
  it('move: posts the request, removes the snippets here and drops their history', async () => {
    const res = transferResponse({ placed: ['solo'], already_on_target: ['shared'], removed_from_source: true });
    api.transfer.mockResolvedValueOnce(res);
    const result = await useCanvasStore.getState().transferSnippets(['solo', 'shared'], target, 'move');
    expect(result).toEqual({ ok: true, value: res });
    expect(api.transfer).toHaveBeenCalledWith('c1', { snippet_ids: ['solo', 'shared'], target_canvas_id: 't', mode: 'move' });
    expect(keys()).toEqual(['other']);
    const { past, future } = useCanvasStore.getState();
    expect(past.map((s) => [...s.snippets.keys()])).toEqual([['other']]);
    expect(future).toEqual([]);
  });

  it('show: adds the target to the badge list here and in history, without a history entry', async () => {
    api.transfer.mockResolvedValueOnce(transferResponse({ placed: ['solo', 'shared'] }));
    const pastBefore = useCanvasStore.getState().past.length;
    const result = await useCanvasStore.getState().transferSnippets(['solo', 'shared'], target, 'show');
    expect(result.ok).toBe(true);
    const state = useCanvasStore.getState();
    expect(state.snippets.get('solo')?.canvases).toEqual([here, target]);
    expect(state.snippets.get('shared')?.canvases).toEqual([album, here, target]);
    expect(state.snippets.get('other')).toBe(other);
    expect(state.past).toHaveLength(pastBefore);
    expect(state.past[2].snippets.get('solo')?.canvases).toEqual([here, target]);
    expect(state.future[0].snippets.get('shared')?.canvases).toEqual([album, here, target]);
    // Undo keeps the up-to-date badge.
    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().snippets.get('solo')?.canvases).toEqual([here, target]);
  });

  it('a failure changes nothing and returns the typed error', async () => {
    api.transfer.mockRejectedValueOnce(new PlacementError('canvas_not_found', 'transfer', 'gone', 404));
    const before = useCanvasStore.getState();
    const result = await useCanvasStore.getState().transferSnippets(['solo'], target, 'move');
    expect(result).toMatchObject({ ok: false, error: { kind: 'canvas_not_found' } });
    expect(useCanvasStore.getState().snippets).toBe(before.snippets);
    expect(useCanvasStore.getState().past).toBe(before.past);
  });

  it('refuses locally: no canvas, nothing selected, the same canvas, too many', async () => {
    const r1 = await useCanvasStore.getState().transferSnippets([], target, 'move');
    expect(r1).toMatchObject({ ok: false, error: { kind: 'nothing_selected' } });
    const r2 = await useCanvasStore.getState().transferSnippets(['solo'], here, 'show');
    expect(r2).toMatchObject({ ok: false, error: { kind: 'same_canvas' } });
    const many = Array.from({ length: 501 }, (_, i) => `s${i}`);
    useCanvasStore.setState({ snippets: new Map(many.map((id) => [id, snippet(id, id)])) });
    const r3 = await useCanvasStore.getState().transferSnippets(many, target, 'show');
    expect(r3).toMatchObject({ ok: false, error: { kind: 'too_many_snippets' } });
    useCanvasStore.setState({ canvasId: null });
    const r4 = await useCanvasStore.getState().transferSnippets(['solo'], target, 'show');
    expect(r4).toMatchObject({ ok: false, error: { kind: 'no_canvas' } });
    expect(api.transfer).not.toHaveBeenCalled();
  });

  it('only sends ids that are on this canvas', async () => {
    api.transfer.mockResolvedValueOnce(transferResponse({ placed: ['solo'] }));
    await useCanvasStore.getState().transferSnippets(['solo', 'gone'], target, 'show');
    expect(api.transfer.mock.calls[0][1].snippet_ids).toEqual(['solo']);
  });
});

describe('renameCanvas', () => {
  it('validates locally without a request', async () => {
    const result = await useCanvasStore.getState().renameCanvas('   ');
    expect(result).toMatchObject({ ok: false, error: { kind: 'invalid_canvas_title', status: null } });
    expect(api.renameCanvas).not.toHaveBeenCalled();
    expect(useCanvasStore.getState().canvasTitle).toBe('Canvas');
  });

  it('saves the trimmed title, updates the header and every embedded canvas list', async () => {
    api.renameCanvas.mockResolvedValueOnce({
      id: 'c1', owner_id: 'u', title: 'Zebra', is_public: false, share_token: null, viewport_state: null, created_at: '', updated_at: '',
    });
    const result = await useCanvasStore.getState().renameCanvas('  Zebra ');
    expect(result.ok).toBe(true);
    expect(api.renameCanvas).toHaveBeenCalledWith('c1', 'Zebra');
    const state = useCanvasStore.getState();
    expect(state.canvasTitle).toBe('Zebra');
    expect(state.snippets.get('shared')?.canvases).toEqual([album, { id: 'c1', title: 'Zebra' }]);
    expect(state.past[2].snippets.get('shared')?.canvases).toEqual([album, { id: 'c1', title: 'Zebra' }]);
  });

  it('returns the server error and keeps the old title', async () => {
    api.renameCanvas.mockRejectedValueOnce(new PlacementError('invalid_canvas_title', 'rename_canvas', 'bad', 422));
    const result = await useCanvasStore.getState().renameCanvas('ok');
    expect(result).toMatchObject({ ok: false, error: { kind: 'invalid_canvas_title', status: 422 } });
    expect(useCanvasStore.getState().canvasTitle).toBe('Canvas');
  });
});
