/**
 * Voice capture UI state: whether the recorder modal is open and where its take goes, the per-viewer
 * silence auto-stop setting, and the hand-off of a finished take into the canvas / editor / list.
 * The recording itself lives in a VoiceSession owned by the modal.
 */
import { create } from 'zustand';
import { browserStorage } from '@/lib/listMode';
import { browserRecordingEnvironment, recordingSupport } from '@/lib/voice/support';
import type { RecordingEnvironment } from '@/lib/voice/support';
import { loadSilenceSeconds, saveSilenceSeconds } from '@/lib/voice/silenceSetting';
import type { SilenceSeconds } from '@/lib/voice/silenceSetting';
import { loadVocabHints, saveVocabHints } from '@/lib/voice/vocabHintsSetting';
import { resolveCaptureTarget } from '@/lib/voice/target';
import type { CaptureTarget } from '@/lib/voice/target';
import type { VoiceTakeResponse } from '@/lib/voice/types';
import { editorBridge } from '@/components/canvas/editorBridge';
import { useCanvasStore } from './canvasStore';
import { useUIStore } from './uiStore';

export interface RecorderRequest {
  canvasId: string;
  target: CaptureTarget;
}

export type OpenRecorderResult =
  | { ok: true; request: RecorderRequest }
  | { ok: false; reason: 'no_canvas' | 'already_open' | 'insecure_context' | 'unsupported' };

interface VoiceState {
  /** The open recorder modal, or null. The target is fixed when it opens. */
  request: RecorderRequest | null;
  silenceSeconds: SilenceSeconds;
  /** "Use word bank as hints": sent as `meta.vocabulary_hints` with every take. Per viewer, default on. */
  vocabHints: boolean;
  /** Bumped whenever a take is stored, so an open recordings list refetches. */
  recordingsRevision: number;
  /** A snippet the list view should scroll to (set after a take creates one); cleared by the list. */
  revealSnippetId: string | null;

  /** Open the recorder targeting the open editor's snippet, or a new snippet at the viewport centre. */
  openRecorder: (env?: RecordingEnvironment) => OpenRecorderResult;
  closeRecorder: () => void;
  setSilenceSeconds: (seconds: SilenceSeconds) => void;
  setVocabHints: (enabled: boolean) => void;
  /** Apply a stored take: update the canvas, refresh the editor (append) or select + reveal (new). */
  completeTake: (result: VoiceTakeResponse) => void;
  clearReveal: (snippetId: string) => void;
}

export const useVoiceStore = create<VoiceState>((set, get) => ({
  request: null,
  silenceSeconds: loadSilenceSeconds(browserStorage()),
  vocabHints: loadVocabHints(browserStorage()),
  recordingsRevision: 0,
  revealSnippetId: null,

  openRecorder: (env = browserRecordingEnvironment()) => {
    if (get().request !== null) return { ok: false, reason: 'already_open' };
    const support = recordingSupport(env);
    if (!support.ok) return { ok: false, reason: support.reason };
    const { canvasId, viewport, canvasViewSize } = useCanvasStore.getState();
    if (!canvasId) return { ok: false, reason: 'no_canvas' };
    const { editingSnippetId } = useUIStore.getState();
    const request: RecorderRequest = { canvasId, target: resolveCaptureTarget({ editingSnippetId, viewport, canvasViewSize }) };
    useUIStore.getState().closeContextMenu();
    set({ request });
    return { ok: true, request };
  },

  closeRecorder: () => set({ request: null }),

  setSilenceSeconds: (seconds) => {
    // A failed write is logged; the in-memory value still applies for this session.
    saveSilenceSeconds(browserStorage(), seconds);
    set({ silenceSeconds: seconds });
  },

  setVocabHints: (enabled) => {
    // A failed write is logged; the in-memory value still applies for this session.
    saveVocabHints(browserStorage(), enabled);
    set({ vocabHints: enabled });
  },

  completeTake: (result) => {
    const canvas = useCanvasStore.getState();
    if (result.snippet.canvas_id !== canvas.canvasId) return;
    canvas.applySnippet(result.snippet);
    if (result.created) {
      useCanvasStore.getState().setSelection(new Set([result.snippet.id]));
      const listView = useUIStore.getState().viewMode === 'list';
      set((s) => ({ recordingsRevision: s.recordingsRevision + 1, revealSnippetId: listView ? result.snippet.id : null }));
      return;
    }
    // Append: the editor still holds the pre-take rows; closing it would save them over the new lines.
    editorBridge(result.snippet.id)?.reload(result.snippet);
    set((s) => ({ recordingsRevision: s.recordingsRevision + 1 }));
  },

  clearReveal: (snippetId) => {
    if (get().revealSnippetId === snippetId) set({ revealSnippetId: null });
  },
}));
