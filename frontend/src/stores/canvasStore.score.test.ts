import { describe, it, expect, vi, beforeEach } from 'vitest';
import { placementApi } from '@/api/client';
import { useCanvasStore } from './canvasStore';
import { snippet } from '@/lib/merge/fixtures';
import type { Group, Snippet } from '@/types';
import type { Score } from '@/types/score';
import { ScoreSaveError } from '@/lib/score/saveFlow';

vi.mock('@/api/client', () => ({
  nlpApi: {},
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
  placementApi: { updateSnippet: vi.fn() },
}));

const api = vi.mocked(placementApi);

const base = snippet('s', 'I go now');
// The fixture grid is one cell per word with word_index; the score places all three.
const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 0,
  meters: [{ bar: 0, num: 4, den: 4 }],
  tempos: [{ bar: 0, bpm: 90 }],
  snap: '1/16',
  lines: [{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 24, d: 24, a: 0 }, { s: 2, t: 48, d: 24, a: 0 }] }],
};
const withScore: Snippet = { ...base, score, score_view: true };

beforeEach(() => {
  vi.clearAllMocks();
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map([[withScore.id, withScore]]),
    groups: new Map<string, Group>(),
    past: [],
    future: [],
  });
});

const current = () => useCanvasStore.getState().snippets.get('s')!;

describe('updateSnippet with a score', () => {
  it('applies the save flow locally before the server answers and sends the final content', async () => {
    let resolve: (s: Snippet) => void = () => undefined;
    api.updateSnippet.mockReturnValueOnce(new Promise<Snippet>((r) => { resolve = r; }));
    const moved: Score = { ...score, lines: [{ notes: [{ s: 0, t: 0, d: 24, a: 0 }, { s: 1, t: 72, d: 24, a: 0 }, { s: 2, t: 96, d: 24, a: 0 }] }] };
    const pending = useCanvasStore.getState().updateSnippet('s', { score: moved });
    expect(current().score).toEqual(moved);
    expect(current().raw_text).toBe('I _ go now');
    const sent = api.updateSnippet.mock.calls[0][2];
    expect(sent).toMatchObject({ score: moved, raw_text: 'I _ go now' });
    expect(sent.grid_data).toEqual(current().grid_data);
    resolve({ ...current() });
    await pending;
    expect(current().raw_text).toBe('I _ go now');
    expect(useCanvasStore.getState().past).toHaveLength(1);
  });

  it('pushes one undo entry; undo restores the score before the edit', async () => {
    api.updateSnippet.mockImplementationOnce(async (_id, _c, body) => ({ ...withScore, ...body }) as Snippet);
    await useCanvasStore.getState().updateSnippet('s', { score_view: false });
    expect(current().score_view).toBe(false);
    useCanvasStore.getState().undo();
    expect(current().score_view).toBe(true);
    expect(current().score).toEqual(score);
    useCanvasStore.getState().redo();
    expect(current().score_view).toBe(false);
  });

  it('Remove score sends an explicit null and turns the staff view off', async () => {
    api.updateSnippet.mockImplementationOnce(async (_id, _c, body) => ({ ...withScore, ...body }) as Snippet);
    await useCanvasStore.getState().updateSnippet('s', { score: null });
    expect(api.updateSnippet).toHaveBeenCalledWith('s', 'c1', { score: null, score_view: false });
    expect(current().score).toBeNull();
    expect(current().score_view).toBe(false);
  });

  it('restores the snippet and drops the undo entry when the server refuses', async () => {
    api.updateSnippet.mockRejectedValueOnce(new Error('422'));
    await expect(useCanvasStore.getState().updateSnippet('s', { score_view: false })).rejects.toThrow('422');
    expect(current()).toBe(withScore);
    expect(useCanvasStore.getState().past).toHaveLength(0);
  });

  it('refuses locally, without a request, a score that does not fit', async () => {
    const bad: Score = { ...score, lines: [{ notes: [{ s: 9, t: 0, d: 1, a: 0 }] }] };
    await expect(useCanvasStore.getState().updateSnippet('s', { score: bad })).rejects.toBeInstanceOf(ScoreSaveError);
    expect(api.updateSnippet).not.toHaveBeenCalled();
    expect(current()).toBe(withScore);
  });

  it('treats a snippet from an older server (no score fields) as having no score', async () => {
    const legacy = { ...base } as Partial<Snippet>;
    delete legacy.score;
    delete legacy.score_view;
    useCanvasStore.setState({ snippets: new Map([['s', legacy as Snippet]]) });
    api.updateSnippet.mockResolvedValueOnce({ ...base, raw_text: 'x' });
    await useCanvasStore.getState().updateSnippet('s', { raw_text: 'x', grid_data: null });
    expect(api.updateSnippet).toHaveBeenCalledWith('s', 'c1', { raw_text: 'x', grid_data: null });
  });
});
