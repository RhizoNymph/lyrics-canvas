import { useEffect, useState } from 'react';
import { useParams, useNavigate, useSearchParams } from 'react-router-dom';
import { useCanvasStore } from '@/stores/canvasStore';
import { useUIStore } from '@/stores/uiStore';
import CanvasViewport from '@/components/canvas/CanvasViewport';
import ListView from '@/components/list/ListView';
import Toolbar from '@/components/toolbar/Toolbar';
import TagExpressionBar from '@/components/toolbar/TagExpressionBar';
import RhymeSidebar from '@/components/sidebar/RhymeSidebar';
import WordBankPanel from '@/components/wordBank/WordBankPanel';
import WordBankToast from '@/components/wordBank/WordBankToast';
import SimilarityPanel from '@/components/sidebar/SimilarityPanel';
import ExportDialog from '@/components/modals/ExportDialog';
import VoiceCaptureModal from '@/components/voice/VoiceCaptureModal';
import { useVoiceStore } from '@/stores/voiceStore';
import { useTagStore } from '@/stores/tagStore';
import { useTagFilterStore } from '@/stores/tagFilterStore';
import { isRecordShortcut } from '@/lib/voice/keys';
import { isWordBankShortcut } from '@/lib/wordBank/keys';
import { useRhymeQuery } from '@/hooks/useRhymeQuery';
import { removalFailureNotice } from '@/lib/placements';
import { usePlacementNoticeStore } from '@/stores/placementNoticeStore';
import PlacementToast from '@/components/placements/PlacementToast';
import CanvasesPopover from '@/components/placements/CanvasesPopover';
import { useCanvasSearchStore } from '@/stores/canvasSearchStore';
import { useSnippetFocus } from '@/hooks/useSnippetFocus';

export default function CanvasPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { loadCanvas, undo, redo, removeFromCanvas, mergeSelected } = useCanvasStore();
  const { rightPanel, viewMode, toggleViewMode } = useUIStore();
  const [searchParams, setSearchParams] = useSearchParams();
  useRhymeQuery();
  /** The canvas this page finished loading (null while a load is pending): focus waits for fresh snippets. */
  const [loadedId, setLoadedId] = useState<string | null>(null);
  useSnippetFocus(id ?? null, loadedId, searchParams, setSearchParams);

  // Tags and presets are user-wide: (re)load them whenever the page mounts.
  useEffect(() => {
    void useTagStore.getState().load();
  }, []);

  useEffect(() => {
    let left = false;
    if (id) {
      useTagFilterStore.getState().open(id);
      useCanvasSearchStore.getState().open(id);
      setLoadedId(null);
      loadCanvas(id)
        .then(() => {
          if (!left) setLoadedId(id);
        })
        .catch(() => navigate('/'));
    }
    // Leaving the canvas closes the recorder (its session releases the mic on unmount), and the editor and
    // menus, whose snippet belongs to the canvas left (another canvas may open in this same page).
    return () => {
      left = true;
      useVoiceStore.getState().closeRecorder();
      useUIStore.getState().leaveCanvas();
    };
  }, [id]);

  // Keyboard shortcuts
  useEffect(() => {
    const typingOrEditing = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
      // Keys pressed inside the word bank (e.g. Delete on a focused word) never act on canvas snippets.
      if (e.target instanceof Element && e.target.closest('[data-word-bank]') !== null) return true;
      return useUIStore.getState().editingSnippetId !== null;
    };
    const handler = (e: KeyboardEvent) => {
      // The recording modal owns the keyboard while open (Space/Enter = next line, Esc = cancel).
      if (useVoiceStore.getState().request !== null) return;
      const ctrl = e.ctrlKey || e.metaKey;
      const key = e.key.toLowerCase();
      if (isRecordShortcut(e)) {
        // Alt+Shift+R: record by voice (appends to the open editor, else a new snippet). Works while typing.
        e.preventDefault();
        useVoiceStore.getState().openRecorder();
      } else if (isWordBankShortcut(e)) {
        // Ctrl/Cmd+Shift+W (or Alt+Shift+W, which browsers do not reserve): toggle the word bank. Works while typing.
        e.preventDefault();
        useUIStore.getState().toggleWordBank();
      } else if (ctrl && e.shiftKey && key === 'l') {
        // Toggle canvas / list view
        if (typingOrEditing(e)) return;
        e.preventDefault();
        toggleViewMode();
      } else if (ctrl && e.shiftKey && (key === 'v' || key === 'h')) {
        // Merge the selection: Ctrl/Cmd+Shift+V stacks, Ctrl/Cmd+Shift+H joins
        if (typingOrEditing(e)) return;
        if (useCanvasStore.getState().selectedSnippetIds.size < 2) return;
        e.preventDefault();
        mergeSelected(key === 'v' ? 'vertical' : 'horizontal');
      } else if (ctrl && e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        undo();
      } else if (ctrl && e.key === 'z' && e.shiftKey) {
        e.preventDefault();
        redo();
      } else if (ctrl && !e.shiftKey && !e.altKey && key === 'f') {
        // Focus the in-canvas search box (instead of the browser's find). Works while typing.
        e.preventDefault();
        useCanvasSearchStore.getState().requestFocus();
      } else if ((e.key === 'Delete' || e.key === 'Backspace') && !ctrl) {
        // Delete selected snippets — only when not editing text
        if (typingOrEditing(e)) return;
        const ids = useCanvasStore.getState().selectedSnippetIds;
        if (ids.size === 0) return;
        e.preventDefault();
        // The plain Delete: removes from this canvas (deletes for good what is on no other canvas).
        void removeFromCanvas([...ids]).then((summary) => {
          const failure = removalFailureNotice(summary, 'remove');
          if (failure !== null) usePlacementNoticeStore.getState().show(failure, 'error');
        });
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [undo, redo, removeFromCanvas, mergeSelected, toggleViewMode]);

  return (
    <div style={{ height: '100vh', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <Toolbar />
      <TagExpressionBar />
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          {viewMode === 'list' ? <ListView /> : <CanvasViewport />}
          <SimilarityPanel />
        </div>
        {rightPanel === 'rhymes' && <RhymeSidebar />}
        {rightPanel === 'words' && <WordBankPanel />}
      </div>
      <ExportDialog />
      <VoiceCaptureModal />
      <WordBankToast />
      <PlacementToast />
      <CanvasesPopover />
    </div>
  );
}
