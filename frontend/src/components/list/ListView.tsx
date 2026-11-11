import { useEffect, useMemo, useRef } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useTagFilterStore } from '@/stores/tagFilterStore';
import { useCanvasSearchStore } from '@/stores/canvasSearchStore';
import { isSearchActive } from '@/lib/search';
import { useUIStore } from '@/stores/uiStore';
import { useVoiceStore } from '@/stores/voiceStore';
import { visibleSnippets } from '@/lib/canvas';
import { listClick, sortForList } from '@/lib/listMode';
import SnippetContextMenu from '@/components/canvas/SnippetContextMenu';
import ListRow from './ListRow';
import { useFrozenOrder } from './useFrozenOrder';

/**
 * List mode: the canvas's snippets (same tag expression filter and in-canvas search as the canvas) as a vertical list, last content
 * edit first. Rows share the canvas selection, open the inline editor in place, and use the canvas
 * context menu; merge / delete / undo go through the same store actions and page shortcuts.
 */
export default function ListView() {
  const snippets = useCanvasStore((s) => s.snippets);
  const tagFilter = useTagFilterStore((s) => s.filter.applied);
  const search = useCanvasSearchStore((s) => s.query);
  const selectedSnippetIds = useCanvasStore((s) => s.selectedSnippetIds);
  const setSelection = useCanvasStore((s) => s.setSelection);
  const clearSelection = useCanvasStore((s) => s.clearSelection);
  const editingSnippetId = useUIStore((s) => s.editingSnippetId);
  const setEditingSnippet = useUIStore((s) => s.setEditingSnippet);
  const setContextMenu = useUIStore((s) => s.setContextMenu);
  const setCanvasesPopover = useUIStore((s) => s.setCanvasesPopover);
  const anchor = useRef<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const revealSnippetId = useVoiceStore((s) => s.revealSnippetId);
  const clearReveal = useVoiceStore((s) => s.clearReveal);
  const searchRevealId = useCanvasSearchStore((s) => s.revealSnippetId);
  const clearSearchReveal = useCanvasSearchStore((s) => s.clearReveal);

  const sortedIds = useMemo(
    () => sortForList(visibleSnippets(snippets, tagFilter, search)).map((s) => s.id),
    [snippets, tagFilter, search],
  );
  const order = useFrozenOrder(sortedIds, editingSnippetId !== null);

  /** Scroll a rendered row into view and focus it; false when it is not rendered. */
  const scrollToRow = (snippetId: string): boolean => {
    const row = Array.from(listRef.current?.querySelectorAll<HTMLElement>('[data-snippet-id]') ?? []).find(
      (el) => el.dataset.snippetId === snippetId,
    );
    if (!row) return false;
    row.scrollIntoView({ block: 'center', behavior: 'smooth' });
    row.focus({ preventScroll: true });
    return true;
  };

  // Scroll to a snippet a voice take just created (it is already selected).
  useEffect(() => {
    if (revealSnippetId === null) return;
    scrollToRow(revealSnippetId);
    clearReveal(revealSnippetId);
  }, [revealSnippetId, order, clearReveal]);

  // Scroll to a snippet opened from a search hit (already selected; a hiding in-canvas search was cleared).
  useEffect(() => {
    if (searchRevealId === null) return;
    // Wait for the row while it is in the list order (the cleared search may not have re-rendered yet); a
    // snippet the tag filter hides is never rendered, so give up on it.
    if (scrollToRow(searchRevealId) || !order.includes(searchRevealId)) clearSearchReveal(searchRevealId);
  }, [searchRevealId, order, clearSearchReveal]);

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

  const emptyText =
    tagFilter !== null && isSearchActive(search)
      ? 'No snippets match the search and the tag filter.'
      : isSearchActive(search)
        ? 'No snippets match the search.'
        : tagFilter !== null
          ? 'No snippets match the tag filter.'
          : 'No snippets yet. Switch to the canvas and double-click to add one.';

  return (
    <div
      ref={listRef}
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
            {emptyText}
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
              onShowCanvases={(x, y) => setCanvasesPopover({ snippetId: id, x, y })}
            />
          );
        })}
      </div>
      <SnippetContextMenu />
    </div>
  );
}
