import { useMemo, useRef } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import { visibleSnippets } from '@/lib/canvas';
import { listClick, sortForList } from '@/lib/listMode';
import SnippetContextMenu from '@/components/canvas/SnippetContextMenu';
import ListRow from './ListRow';
import { useFrozenOrder } from './useFrozenOrder';

/**
 * List mode: the canvas's snippets (same tag filter as the canvas) as a vertical list, last content
 * edit first. Rows share the canvas selection, open the inline editor in place, and use the canvas
 * context menu; merge / delete / undo go through the same store actions and page shortcuts.
 */
export default function ListView() {
  const snippets = useCanvasStore((s) => s.snippets);
  const activeTagFilters = useCanvasStore((s) => s.activeTagFilters);
  const selectedSnippetIds = useCanvasStore((s) => s.selectedSnippetIds);
  const setSelection = useCanvasStore((s) => s.setSelection);
  const clearSelection = useCanvasStore((s) => s.clearSelection);
  const editingSnippetId = useUIStore((s) => s.editingSnippetId);
  const setEditingSnippet = useUIStore((s) => s.setEditingSnippet);
  const setContextMenu = useUIStore((s) => s.setContextMenu);
  const anchor = useRef<string | null>(null);

  const sortedIds = useMemo(
    () => sortForList(visibleSnippets(snippets, activeTagFilters)).map((s) => s.id),
    [snippets, activeTagFilters],
  );
  const order = useFrozenOrder(sortedIds, editingSnippetId !== null);

  const onRowClick = (id: string, e: ReactMouseEvent) => {
    const result = listClick(
      { selection: selectedSnippetIds, anchor: anchor.current, order },
      id,
      { shift: e.shiftKey, toggle: e.ctrlKey || e.metaKey },
    );
    anchor.current = result.anchor;
    setSelection(result.selection);
    if (result.openEditor) setEditingSnippet(id);
  };

  const openRow = (id: string) => {
    anchor.current = id;
    setSelection(new Set([id]));
    setEditingSnippet(id);
  };

  const onRowContextMenu = (id: string, e: ReactMouseEvent) => {
    e.preventDefault();
    setContextMenu({ snippetId: id, x: e.clientX, y: e.clientY });
    // Right-clicking a member of a multi-selection keeps it so the menu can merge the set (as on the canvas).
    if (!selectedSnippetIds.has(id)) {
      anchor.current = id;
      setSelection(new Set([id]));
    }
  };

  const onBackgroundClick = (e: ReactMouseEvent) => {
    if (e.target === e.currentTarget && editingSnippetId === null) clearSelection();
  };

  const filtered = activeTagFilters.size > 0;

  return (
    <div
      role="listbox"
      aria-multiselectable
      aria-label="Snippets, last edited first"
      onClick={onBackgroundClick}
      onContextMenu={(e) => e.preventDefault()}
      style={{ flex: 1, overflowY: 'auto', background: '#12122a', padding: '16px 16px 48px' }}
    >
      <div onClick={onBackgroundClick} style={{ maxWidth: 960, margin: '0 auto' }}>
        {order.length === 0 && (
          <div style={{ color: '#6a6a8a', fontSize: 13, textAlign: 'center', marginTop: 48 }}>
            {filtered ? 'No snippets carry the active tags.' : 'No snippets yet. Switch to the canvas and double-click to add one.'}
          </div>
        )}
        {order.map((id) => {
          const snippet = snippets.get(id);
          if (!snippet) return null;
          return (
            <ListRow
              key={id}
              snippet={snippet}
              selected={selectedSnippetIds.has(id)}
              editing={editingSnippetId === id}
              onClick={(e) => onRowClick(id, e)}
              onContextMenu={(e) => onRowContextMenu(id, e)}
              onOpen={() => openRow(id)}
            />
          );
        })}
      </div>
      <SnippetContextMenu />
    </div>
  );
}
