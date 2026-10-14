import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useCanvasStore } from './canvasStore';
import { snippet } from '@/lib/merge/fixtures';

vi.mock('@/api/client', () => ({
  nlpApi: {},
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
}));

beforeEach(() => {
  useCanvasStore.setState({
    snippets: new Map(['a', 'b', 'c'].map((id) => [id, snippet(id, id)])),
    selectedSnippetIds: new Set(['a']),
  });
});

describe('canvasStore.setSelection', () => {
  it('replaces the shared selection with the given ids', () => {
    useCanvasStore.getState().setSelection(new Set(['b', 'c']));
    expect([...useCanvasStore.getState().selectedSnippetIds].sort()).toEqual(['b', 'c']);
  });

  it('drops ids that are not snippets on this canvas', () => {
    useCanvasStore.getState().setSelection(new Set(['b', 'ghost']));
    expect([...useCanvasStore.getState().selectedSnippetIds]).toEqual(['b']);
  });

  it('stores a copy, not the caller set', () => {
    const ids = new Set(['b']);
    useCanvasStore.getState().setSelection(ids);
    ids.add('c');
    expect([...useCanvasStore.getState().selectedSnippetIds]).toEqual(['b']);
  });

  it('is the same selection mergeSelected reads (list and canvas share it)', () => {
    useCanvasStore.getState().setSelection(new Set(['a', 'c']));
    useCanvasStore.getState().selectSnippet('b', true);
    expect([...useCanvasStore.getState().selectedSnippetIds].sort()).toEqual(['a', 'b', 'c']);
  });
});
