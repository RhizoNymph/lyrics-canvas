import { useRef, useEffect, useCallback, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import { usePlacementNoticeStore } from '@/stores/placementNoticeStore';
import type { MergeMode } from '@/lib/merge';
import { pickerTargets } from '@/lib/tags/picker';
import {
  deleteEverywhereConfirm,
  deleteMenu,
  describePlacementError,
  removalFailureNotice,
  transferNotice,
} from '@/lib/placements';
import type { CanvasRef, TransferMode } from '@/types';
import TagPicker from '@/components/tags/TagPicker';
import CanvasPicker from '@/components/placements/CanvasPicker';

/** What the menu shows: the actions, a canvas picker for a transfer, or the Delete everywhere confirmation. */
type MenuView = { kind: 'main' } | { kind: 'picker'; mode: TransferMode } | { kind: 'confirm_delete_everywhere' };

const itemStyle: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '6px 12px',
  cursor: 'pointer',
};

function Item({ icon, label, title, danger = false, onClick }: { icon: string; label: string; title?: string; danger?: boolean; onClick: () => void }) {
  const hover = danger ? 'rgba(239, 68, 68, 0.1)' : 'rgba(99, 102, 241, 0.15)';
  return (
    <div
      role="menuitem"
      onClick={onClick}
      style={{ ...itemStyle, ...(danger ? { color: '#ef4444' } : {}) }}
      onMouseEnter={(e) => (e.currentTarget.style.background = hover)}
      onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
      title={title}
    >
      <span style={{ width: 16, textAlign: 'center', fontSize: 13 }}>{icon}</span>
      <span>{label}</span>
    </div>
  );
}

const divider: ReactNode = <div style={{ height: 1, background: '#3a3a5c', margin: '4px 0' }} />;

export default function SnippetContextMenu() {
  const { contextMenu, closeContextMenu } = useUIStore();
  const { canvasId, snippets, selectedSnippetIds, mergeSelected, removeFromCanvas, deleteEverywhere, transferSnippets } =
    useCanvasStore();
  const showNotice = usePlacementNoticeStore((s) => s.show);
  const menuRef = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<MenuView>({ kind: 'main' });

  // A new menu starts on the actions.
  useEffect(() => setView({ kind: 'main' }), [contextMenu]);

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

  // The snippets every action applies to: the multi-selection when opened on one of its members.
  const targetIds = useCallback(
    () => (contextMenu ? pickerTargets(contextMenu.snippetId, selectedSnippetIds, snippets).map((s) => s.id) : []),
    [contextMenu, selectedSnippetIds, snippets],
  );

  const handleRemove = useCallback(async () => {
    const ids = targetIds();
    closeContextMenu();
    const summary = await removeFromCanvas(ids);
    const failure = removalFailureNotice(summary, 'remove');
    if (failure !== null) showNotice(failure, 'error');
  }, [targetIds, removeFromCanvas, closeContextMenu, showNotice]);

  const handleDeleteEverywhere = useCallback(async () => {
    const ids = targetIds();
    closeContextMenu();
    const summary = await deleteEverywhere(ids);
    const failure = removalFailureNotice(summary, 'delete_everywhere');
    if (failure !== null) showNotice(failure, 'error');
    else showNotice(`Deleted ${summary.removed.length} snippet${summary.removed.length === 1 ? '' : 's'} everywhere`, 'info');
  }, [targetIds, deleteEverywhere, closeContextMenu, showNotice]);

  const handleTransfer = useCallback(
    async (target: CanvasRef, mode: TransferMode) => {
      const ids = targetIds();
      closeContextMenu();
      const result = await transferSnippets(ids, target, mode);
      if (result.ok) showNotice(transferNotice(mode, target.title, result.value), 'info', target);
      else showNotice(describePlacementError(result.error), 'error');
    },
    [targetIds, transferSnippets, closeContextMenu, showNotice],
  );

  const handleMerge = useCallback(async (mode: MergeMode) => {
    closeContextMenu();
    await mergeSelected(mode);
  }, [mergeSelected, closeContextMenu]);

  if (!contextMenu || canvasId === null) return null;

  const snippet = snippets.get(contextMenu.snippetId);
  if (!snippet) return null;

  const targets = pickerTargets(contextMenu.snippetId, selectedSnippetIds, snippets);
  const canMerge = selectedSnippetIds.size >= 2 && selectedSnippetIds.has(snippet.id);
  const del = deleteMenu(targets, canvasId);
  const many = targets.length > 1 ? ` (${targets.length})` : '';

  return (
    <div
      ref={menuRef}
      role="menu"
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
      {view.kind === 'picker' && (
        <CanvasPicker
          currentCanvasId={canvasId}
          mode={view.mode}
          count={targets.length}
          onPick={(target) => void handleTransfer(target, view.mode)}
          onBack={() => setView({ kind: 'main' })}
        />
      )}

      {view.kind === 'confirm_delete_everywhere' && (
        <div role="alertdialog" aria-label="Delete everywhere" style={{ padding: '8px 12px' }}>
          <div style={{ marginBottom: 10, lineHeight: 1.4 }}>{deleteEverywhereConfirm(targets, canvasId)}</div>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button
              type="button"
              onClick={() => setView({ kind: 'main' })}
              style={{ background: '#2a2a4a', color: '#ccc', border: '1px solid #3a3a5a', borderRadius: 4, padding: '4px 10px', cursor: 'pointer' }}
            >
              Cancel
            </button>
            <button
              type="button"
              autoFocus
              onClick={() => void handleDeleteEverywhere()}
              style={{ background: '#b91c1c', color: '#fff', border: 'none', borderRadius: 4, padding: '4px 10px', cursor: 'pointer' }}
            >
              Delete everywhere
            </button>
          </div>
        </div>
      )}

      {view.kind === 'main' && (
        <>
          {/* Tag picker: searchable checklist, create, edit and delete (applies to the whole multi-selection) */}
          <TagPicker key={contextMenu.snippetId} contextSnippetId={contextMenu.snippetId} />

          {divider}

          {/* Merge the multi-selection (this snippet included) into one block */}
          {canMerge && (
            <>
              <Item
                icon="⇣"
                label="Merge vertically"
                title={`Stack the ${selectedSnippetIds.size} selected snippets top to bottom (Ctrl+Shift+V)`}
                onClick={() => void handleMerge('vertical')}
              />
              <Item
                icon="⇢"
                label="Merge horizontally"
                title={`Join the ${selectedSnippetIds.size} selected snippets left to right (Ctrl+Shift+H)`}
                onClick={() => void handleMerge('horizontal')}
              />
              {divider}
            </>
          )}

          {/* Placements: the same snippet on other canvases, with its own layout there */}
          <Item
            icon="→"
            label={`Move to canvas…${many}`}
            title="Take the snippets off this canvas and put them on another one"
            onClick={() => setView({ kind: 'picker', mode: 'move' })}
          />
          <Item
            icon="⧉"
            label={`Also show on canvas…${many}`}
            title="Keep the snippets here and show the same snippets on another canvas too (edits show on both)"
            onClick={() => setView({ kind: 'picker', mode: 'show' })}
          />

          {divider}

          <Item
            icon="✕"
            label={`${del.removeLabel}${del.anyShared ? many : ''}`}
            title={del.anyShared ? 'Remove from this canvas only; the snippets stay on their other canvases' : undefined}
            danger
            onClick={() => void handleRemove()}
          />
          {del.offerDeleteEverywhere && (
            <Item
              icon="⊘"
              label={`Delete everywhere${many}`}
              title="Delete the snippets from every canvas, with their recordings"
              danger
              onClick={() => setView({ kind: 'confirm_delete_everywhere' })}
            />
          )}
        </>
      )}
    </div>
  );
}
