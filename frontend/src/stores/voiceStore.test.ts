import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useVoiceStore } from './voiceStore';
import { useCanvasStore } from './canvasStore';
import { useUIStore } from './uiStore';
import { registerEditorBridge } from '@/components/canvas/editorBridge';
import { snippet } from '@/lib/merge/fixtures';
import { SILENCE_STORAGE_KEY } from '@/lib/voice/silenceSetting';
import type { RecordingResponse, VoiceTakeResponse } from '@/lib/voice/types';
import type { Snippet } from '@/types';

vi.mock('@/api/client', () => ({
  nlpApi: {},
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
}));

const secure = { isSecureContext: true, hasMediaDevices: true, hasMediaRecorder: true };
const existing = snippet('s1', 'old line', { position_x: 0, position_y: 0 });

function take(s: Snippet, created: boolean): VoiceTakeResponse {
  return {
    snippet: s,
    recording: { id: 'r1', snippet_id: s.id } as RecordingResponse,
    lines: ['new line'],
    split_mode: 'single',
    created,
  };
}

let data: Record<string, string>;

beforeEach(() => {
  data = {};
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => (k in data ? data[k] : null),
    setItem: (k: string, v: string) => {
      data[k] = v;
    },
  });
  useCanvasStore.setState({
    canvasId: 'c1',
    viewport: { x: -100, y: 0, zoom: 1 },
    canvasViewSize: { width: 800, height: 600 },
    snippets: new Map([[existing.id, existing]]),
    selectedSnippetIds: new Set(),
    past: [],
    future: [],
  });
  useUIStore.setState({ viewMode: 'canvas', editingSnippetId: null, contextMenu: null });
  useVoiceStore.setState({ request: null, silenceSeconds: 0, recordingsRevision: 0, revealSnippetId: null });
});

afterEach(() => vi.unstubAllGlobals());

describe('openRecorder', () => {
  it('targets a new snippet at the viewport centre when no editor is open', () => {
    const r = useVoiceStore.getState().openRecorder(secure);
    expect(r).toEqual({ ok: true, request: { canvasId: 'c1', target: { kind: 'new', position: { x: 500, y: 300 } } } });
    expect(useVoiceStore.getState().request).not.toBeNull();
  });

  it('targets the open editor snippet for append', () => {
    useUIStore.setState({ editingSnippetId: 's1' });
    const r = useVoiceStore.getState().openRecorder(secure);
    expect(r.ok && r.request.target).toEqual({ kind: 'append', snippetId: 's1' });
  });

  it('in list view uses the last measured canvas viewport, or the origin when never measured', () => {
    useUIStore.setState({ viewMode: 'list' });
    const a = useVoiceStore.getState().openRecorder(secure);
    expect(a.ok && a.request.target).toEqual({ kind: 'new', position: { x: 500, y: 300 } });
    useVoiceStore.getState().closeRecorder();
    useCanvasStore.setState({ canvasViewSize: null });
    const b = useVoiceStore.getState().openRecorder(secure);
    expect(b.ok && b.request.target).toEqual({ kind: 'new', position: { x: 0, y: 0 } });
  });

  it('refuses when insecure, unsupported, already open or without a canvas', () => {
    expect(useVoiceStore.getState().openRecorder({ ...secure, isSecureContext: false })).toEqual({ ok: false, reason: 'insecure_context' });
    expect(useVoiceStore.getState().openRecorder({ ...secure, hasMediaRecorder: false })).toEqual({ ok: false, reason: 'unsupported' });
    useVoiceStore.getState().openRecorder(secure);
    expect(useVoiceStore.getState().openRecorder(secure)).toEqual({ ok: false, reason: 'already_open' });
    useVoiceStore.getState().closeRecorder();
    useCanvasStore.setState({ canvasId: null });
    expect(useVoiceStore.getState().openRecorder(secure)).toEqual({ ok: false, reason: 'no_canvas' });
  });
});

describe('completeTake', () => {
  it('a new snippet is added without a reload, selected, and revealed in list view', () => {
    useUIStore.setState({ viewMode: 'list' });
    const created = snippet('s2', 'new line', { position_x: 500, position_y: 300 });
    useVoiceStore.getState().completeTake(take(created, true));
    const canvas = useCanvasStore.getState();
    expect(canvas.snippets.get('s2')).toEqual(created);
    expect(canvas.snippets.get('s1')).toEqual(existing);
    expect([...canvas.selectedSnippetIds]).toEqual(['s2']);
    expect(useVoiceStore.getState().revealSnippetId).toBe('s2');
    useVoiceStore.getState().clearReveal('s2');
    expect(useVoiceStore.getState().revealSnippetId).toBeNull();
  });

  it('in canvas view selects but does not ask the list to scroll', () => {
    const created = snippet('s2', 'new line');
    useVoiceStore.getState().completeTake(take(created, true));
    expect(useVoiceStore.getState().revealSnippetId).toBeNull();
    expect([...useCanvasStore.getState().selectedSnippetIds]).toEqual(['s2']);
  });

  it('an append replaces the snippet in the store and reloads the open editor from it', () => {
    const reload = vi.fn();
    const off = registerEditorBridge('s1', { flush: async () => undefined, reload });
    const appended = snippet('s1', 'old line\nnew line');
    useVoiceStore.getState().completeTake(take(appended, false));
    expect(useCanvasStore.getState().snippets.get('s1')).toEqual(appended);
    expect(reload).toHaveBeenCalledWith(appended);
    expect(useVoiceStore.getState().recordingsRevision).toBe(1);
    off();
  });

  it('ignores a take for another canvas (the user navigated away)', () => {
    const foreign = { ...snippet('x', 'line'), canvas_id: 'other' };
    useVoiceStore.getState().completeTake(take(foreign, true));
    expect(useCanvasStore.getState().snippets.has('x')).toBe(false);
  });
});

describe('silence setting', () => {
  it('persists per viewer', () => {
    useVoiceStore.getState().setSilenceSeconds(5);
    expect(useVoiceStore.getState().silenceSeconds).toBe(5);
    expect(data[SILENCE_STORAGE_KEY]).toBe('5');
  });

  it('still applies in memory when storage throws', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    });
    useVoiceStore.getState().setSilenceSeconds(3);
    expect(useVoiceStore.getState().silenceSeconds).toBe(3);
    warn.mockRestore();
  });
});
