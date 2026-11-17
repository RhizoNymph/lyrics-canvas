import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useCanvasStore } from './canvasStore';
import { snippet } from '@/lib/merge/fixtures';
import type { Group, Snippet } from '@/types';

vi.mock('@/api/client', () => ({
  nlpApi: {},
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
  placementApi: {},
}));

const a = snippet('a', 'a', { list_rank: 'a0' });
const b = snippet('b', 'b', { list_rank: 'a1' });

function snap(...snippets: Snippet[]) {
  return { snippets: new Map(snippets.map((s) => [s.id, s])), groups: new Map<string, Group>() };
}

beforeEach(() => {
  useCanvasStore.setState({
    canvasId: 'c1',
    snippets: new Map([a, b].map((s) => [s.id, s])),
    past: [snap(a), snap(a, b)],
    future: [snap(b)],
  });
});

describe('applyListRanks', () => {
  it('patches the live map and every undo/redo snapshot, without a new history entry', () => {
    useCanvasStore.getState().applyListRanks([{ id: 'a', list_rank: 'a2' }]);
    const s = useCanvasStore.getState();
    expect(s.snippets.get('a')!.list_rank).toBe('a2');
    expect(s.past).toHaveLength(2);
    expect(s.future).toHaveLength(1);
    expect(s.past.map((p) => p.snippets.get('a')?.list_rank)).toEqual(['a2', 'a2']);
    // The snapshot without `a` is untouched.
    expect(s.future[0].snippets.get('b')!.list_rank).toBe('a1');
  });

  it('so undoing an earlier edit never reverts the list order', () => {
    useCanvasStore.getState().applyListRanks([{ id: 'b', list_rank: 'Zz' }]);
    useCanvasStore.getState().undo();
    expect(useCanvasStore.getState().snippets.get('b')!.list_rank).toBe('Zz');
  });

  it('honours `expect` (rollback only where the optimistic rank is still in place)', () => {
    useCanvasStore.getState().applyListRanks([
      { id: 'a', list_rank: 'Zz', expect: 'a0' },
      { id: 'b', list_rank: 'Zy', expect: 'a5' },
    ]);
    const s = useCanvasStore.getState();
    expect(s.snippets.get('a')!.list_rank).toBe('Zz');
    expect(s.snippets.get('b')!.list_rank).toBe('a1');
  });

  it('keeps the same state objects when nothing changes', () => {
    const before = useCanvasStore.getState();
    before.applyListRanks([{ id: 'nope', list_rank: 'a5' }]);
    const after = useCanvasStore.getState();
    expect(after.snippets).toBe(before.snippets);
    expect(after.past).toBe(before.past);
  });
});
