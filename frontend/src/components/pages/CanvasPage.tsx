import { useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import CanvasViewport from '@/components/canvas/CanvasViewport';
import Toolbar from '@/components/toolbar/Toolbar';
import TagFilterBar from '@/components/toolbar/TagFilterBar';
import RhymeSidebar from '@/components/sidebar/RhymeSidebar';
import SimilarityPanel from '@/components/sidebar/SimilarityPanel';
import SearchModal from '@/components/modals/SearchModal';
import ExportDialog from '@/components/modals/ExportDialog';

export default function CanvasPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { loadCanvas, undo, redo, selectedSnippetIds, deleteSnippet } = useCanvasStore();
  const { setSearchModalOpen } = useUIStore();

  useEffect(() => {
    if (id) {
      loadCanvas(id).catch(() => navigate('/'));
    }
  }, [id]);

  // Keyboard shortcuts
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const ctrl = e.ctrlKey || e.metaKey;
      if (ctrl && e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        undo();
      } else if (ctrl && e.key === 'z' && e.shiftKey) {
        e.preventDefault();
        redo();
      } else if (ctrl && e.key === 'f') {
        e.preventDefault();
        setSearchModalOpen(true);
      } else if ((e.key === 'Delete' || e.key === 'Backspace') && !ctrl) {
        // Delete selected snippets — only when not editing text
        const tag = (e.target as HTMLElement)?.tagName;
        if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
        const { editingSnippetId } = useUIStore.getState();
        if (editingSnippetId) return;
        const ids = useCanvasStore.getState().selectedSnippetIds;
        if (ids.size === 0) return;
        e.preventDefault();
        for (const sid of ids) {
          deleteSnippet(sid);
        }
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [undo, redo, setSearchModalOpen, deleteSnippet]);

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <Toolbar />
      <TagFilterBar />
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          <CanvasViewport />
          <SimilarityPanel />
        </div>
        <RhymeSidebar />
      </div>
      <SearchModal />
      <ExportDialog />
    </div>
  );
}
