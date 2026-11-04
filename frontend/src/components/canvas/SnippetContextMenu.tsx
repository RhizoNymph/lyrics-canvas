import { useRef, useEffect, useCallback } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import type { MergeMode } from '@/lib/merge';
import TagPicker from '@/components/tags/TagPicker';

export default function SnippetContextMenu() {
  const { contextMenu, closeContextMenu } = useUIStore();
  const { snippets, selectedSnippetIds, deleteSnippet, mergeSelected } = useCanvasStore();
  const menuRef = useRef<HTMLDivElement>(null);

  // Click-outside or Escape closes
  useEffect(() => {
    if (!contextMenu) return;
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        closeContextMenu();
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeContextMenu();
    };
    const id = setTimeout(() => document.addEventListener('mousedown', handler), 50);
    document.addEventListener('keydown', onKey);
    return () => {
      clearTimeout(id);
      document.removeEventListener('mousedown', handler);
      document.removeEventListener('keydown', onKey);
    };
  }, [contextMenu, closeContextMenu]);

  const handleDelete = useCallback(async () => {
    if (!contextMenu) return;
    await deleteSnippet(contextMenu.snippetId);
    closeContextMenu();
  }, [contextMenu, deleteSnippet, closeContextMenu]);

  const handleMerge = useCallback(async (mode: MergeMode) => {
    closeContextMenu();
    await mergeSelected(mode);
  }, [mergeSelected, closeContextMenu]);

  if (!contextMenu) return null;

  const snippet = snippets.get(contextMenu.snippetId);
  if (!snippet) return null;

  const canMerge = selectedSnippetIds.size >= 2 && selectedSnippetIds.has(snippet.id);

  const itemStyle = {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '6px 12px',
    cursor: 'pointer',
  } as const;

  return (
    <div
      ref={menuRef}
      style={{
        position: 'fixed',
        left: contextMenu.x,
        top: contextMenu.y,
        zIndex: 2000,
        background: '#1e1e3a',
        border: '1px solid #3a3a5c',
        borderRadius: 8,
        padding: '4px 0',
        minWidth: 260,
        maxWidth: 320,
        boxShadow: '0 8px 24px rgba(0,0,0,0.5)',
        fontFamily: 'system-ui, sans-serif',
        fontSize: 13,
        color: '#d0d0e8',
      }}
      onClick={(e) => e.stopPropagation()}
    >
      {/* Tag picker: searchable checklist, create, edit and delete (applies to the whole multi-selection) */}
      <TagPicker key={contextMenu.snippetId} contextSnippetId={contextMenu.snippetId} />

      {/* Divider */}
      <div style={{ height: 1, background: '#3a3a5c', margin: '4px 0' }} />

      {/* Merge the multi-selection (this snippet included) into one block */}
      {canMerge && (
        <>
          <div
            onClick={() => handleMerge('vertical')}
            style={itemStyle}
            onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(99, 102, 241, 0.15)')}
            onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
            title={`Stack the ${selectedSnippetIds.size} selected snippets top to bottom (Ctrl+Shift+V)`}
          >
            <span style={{ width: 16, textAlign: 'center', fontSize: 13 }}>⇣</span>
            <span>Merge vertically</span>
          </div>
          <div
            onClick={() => handleMerge('horizontal')}
            style={itemStyle}
            onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(99, 102, 241, 0.15)')}
            onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
            title={`Join the ${selectedSnippetIds.size} selected snippets left to right (Ctrl+Shift+H)`}
          >
            <span style={{ width: 16, textAlign: 'center', fontSize: 13 }}>⇢</span>
            <span>Merge horizontally</span>
          </div>
          <div style={{ height: 1, background: '#3a3a5c', margin: '4px 0' }} />
        </>
      )}

      {/* Delete snippet */}
      <div
        onClick={handleDelete}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '6px 12px',
          cursor: 'pointer',
          color: '#ef4444',
        }}
        onMouseEnter={(e) => (e.currentTarget.style.background = 'rgba(239, 68, 68, 0.1)')}
        onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
      >
        <span style={{ width: 16, textAlign: 'center', fontSize: 13 }}>✕</span>
        <span>Delete snippet</span>
      </div>
    </div>
  );
}
