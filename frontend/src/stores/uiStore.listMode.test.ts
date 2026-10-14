import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { useUIStore } from './uiStore';
import { VIEW_MODE_STORAGE_KEY } from '@/lib/listMode';

let data: Record<string, string>;

beforeEach(() => {
  data = {};
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => (k in data ? data[k] : null),
    setItem: (k: string, v: string) => {
      data[k] = v;
    },
  });
  useUIStore.setState({ viewMode: 'canvas', editingSnippetId: null, contextMenu: null });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('uiStore: view mode', () => {
  it('starts in canvas mode when nothing is stored (node has no localStorage at import)', () => {
    expect(useUIStore.getInitialState().viewMode).toBe('canvas');
  });

  it('setViewMode switches and persists the mode per viewer', () => {
    useUIStore.getState().setViewMode('list');
    expect(useUIStore.getState().viewMode).toBe('list');
    expect(data[VIEW_MODE_STORAGE_KEY]).toBe('list');
  });

  it('toggleViewMode flips between canvas and list and persists each time', () => {
    useUIStore.getState().toggleViewMode();
    expect(useUIStore.getState().viewMode).toBe('list');
    expect(data[VIEW_MODE_STORAGE_KEY]).toBe('list');
    useUIStore.getState().toggleViewMode();
    expect(useUIStore.getState().viewMode).toBe('canvas');
    expect(data[VIEW_MODE_STORAGE_KEY]).toBe('canvas');
  });

  it('switching modes closes the editor and the context menu (their anchors belong to the old view)', () => {
    useUIStore.setState({ editingSnippetId: 's1', contextMenu: { snippetId: 's1', x: 1, y: 2 } });
    useUIStore.getState().setViewMode('list');
    expect(useUIStore.getState().editingSnippetId).toBeNull();
    expect(useUIStore.getState().contextMenu).toBeNull();
  });

  it('still switches when storage throws', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    });
    useUIStore.getState().setViewMode('list');
    expect(useUIStore.getState().viewMode).toBe('list');
  });
});

describe('uiStore: closeEditor', () => {
  it('clears the editor when it is still editing that snippet', () => {
    useUIStore.setState({ editingSnippetId: 'a' });
    useUIStore.getState().closeEditor('a');
    expect(useUIStore.getState().editingSnippetId).toBeNull();
  });

  it('leaves a newer editor open (a slow save of the previous one must not close it)', () => {
    useUIStore.setState({ editingSnippetId: 'b' });
    useUIStore.getState().closeEditor('a');
    expect(useUIStore.getState().editingSnippetId).toBe('b');
  });
});
