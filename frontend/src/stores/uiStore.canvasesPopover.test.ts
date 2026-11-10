import { describe, it, expect, beforeEach } from 'vitest';

import { useUIStore } from './uiStore';

beforeEach(() => {
  useUIStore.setState({ contextMenu: null, canvasesPopover: null, editingSnippetId: null });
});

describe('uiStore canvases popover', () => {
  it('opening the popover closes the context menu', () => {
    useUIStore.getState().setContextMenu({ snippetId: 's', x: 1, y: 2 });
    useUIStore.getState().setCanvasesPopover({ snippetId: 's', x: 3, y: 4 });
    expect(useUIStore.getState().contextMenu).toBeNull();
    expect(useUIStore.getState().canvasesPopover).toEqual({ snippetId: 's', x: 3, y: 4 });
    useUIStore.getState().setCanvasesPopover(null);
    expect(useUIStore.getState().canvasesPopover).toBeNull();
  });

  it('leaving the canvas closes the editor, menu and popover', () => {
    useUIStore.setState({ editingSnippetId: 'e', contextMenu: { snippetId: 's', x: 0, y: 0 }, canvasesPopover: { snippetId: 's', x: 0, y: 0 } });
    useUIStore.getState().leaveCanvas();
    const s = useUIStore.getState();
    expect([s.editingSnippetId, s.contextMenu, s.canvasesPopover]).toEqual([null, null, null]);
  });

  it('switching the view closes the popover', () => {
    useUIStore.setState({ canvasesPopover: { snippetId: 's', x: 0, y: 0 } });
    useUIStore.getState().setViewMode('list');
    expect(useUIStore.getState().canvasesPopover).toBeNull();
  });
});
