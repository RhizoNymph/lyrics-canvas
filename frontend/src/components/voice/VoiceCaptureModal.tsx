import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { voiceApi } from '@/api/client';
import { editorBridge } from '@/components/canvas/editorBridge';
import { startMediaCapture } from '@/lib/voice/mediaCapture';
import { VoiceSession } from '@/lib/voice/session';
import type { SessionSnapshot } from '@/lib/voice/session';
import { uploadFileName } from '@/lib/voice/support';
import { toWireTarget } from '@/lib/voice/target';
import { useVoiceStore } from '@/stores/voiceStore';
import type { RecorderRequest } from '@/stores/voiceStore';
import VoiceCaptureView from './VoiceCaptureView';
import { useFocusTrap } from './useFocusTrap';

/** What the dialog shows before its session exists (the effect creates it right after mount). */
const PRE_START: SessionSnapshot = { recorder: { kind: 'requesting_permission' }, tapPending: false, elapsedMs: 0, level: 0 };
const noSubscribe = () => () => undefined;
const preStart = () => PRE_START;

function useSessionSnapshot(session: VoiceSession | null): SessionSnapshot {
  return useSyncExternalStore(session ? session.subscribe : noSubscribe, session ? session.getSnapshot : preStart);
}

function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

function VoiceCaptureDialog({ request }: { request: RecorderRequest }) {
  const closeRecorder = useVoiceStore((s) => s.closeRecorder);
  const silenceSeconds = useVoiceStore((s) => s.silenceSeconds);
  const setSilenceSeconds = useVoiceStore((s) => s.setSilenceSeconds);
  const vocabHints = useVoiceStore((s) => s.vocabHints);
  const setVocabHints = useVoiceStore((s) => s.setVocabHints);
  const surfaceRef = useRef<HTMLDivElement>(null);
  const onTrapKeyDown = useFocusTrap(surfaceRef);
  const [session, setSession] = useState<VoiceSession | null>(null);

  // One session per opened request. Created in the effect (not during render) so StrictMode's
  // mount/unmount/mount gets a fresh session and the disposed one releases its mic.
  useEffect(() => {
    const s = new VoiceSession({
      startCapture: () => startMediaCapture(),
      beforeUpload: async () => {
        if (request.target.kind !== 'append') return;
        // Save the editor's pending edits first so the server appends after them.
        await editorBridge(request.target.snippetId)?.flush();
      },
      // The hints switch is read at upload time, so flipping it during the take (or before Retry) applies.
      upload: (take, signal) =>
        voiceApi.createTake(request.canvasId, take, toWireTarget(request.target), signal, {
          vocabularyHints: useVoiceStore.getState().vocabHints,
        }),
      onDone: (result) => useVoiceStore.getState().completeTake(result),
      now: () => performance.now(),
      silenceSeconds: () => useVoiceStore.getState().silenceSeconds,
    });
    setSession(s);
    s.start();
    return () => s.dispose();
  }, [request]);

  const snapshot = useSessionSnapshot(session);
  const kind = snapshot.recorder.kind;

  useEffect(() => {
    if (kind === 'done') closeRecorder();
  }, [kind, closeRecorder]);

  const cancel = () => {
    session?.cancel();
    closeRecorder();
  };
  const discard = () => {
    session?.discard();
    closeRecorder();
  };
  const saveAudio = () => {
    const state = snapshot.recorder;
    if (state.kind !== 'upload_failed') return;
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    downloadBlob(state.take.blob, `voice-${stamp}-${uploadFileName(state.take.mimeType)}`);
  };

  return (
    <VoiceCaptureView
      snapshot={snapshot}
      target={request.target}
      silenceSeconds={silenceSeconds}
      surfaceRef={surfaceRef}
      onTap={() => session?.tap()}
      onStop={() => session?.stop()}
      onCancel={cancel}
      onRetry={() => session?.retry()}
      onDiscard={discard}
      onSaveAudio={saveAudio}
      onSilenceChange={setSilenceSeconds}
      vocabHints={{ enabled: vocabHints, onChange: setVocabHints }}
      onTrapKeyDown={onTrapKeyDown}
    />
  );
}

/** The voice capture modal, shown while voiceStore.request is set (canvas page, both views). */
export default function VoiceCaptureModal() {
  const request = useVoiceStore((s) => s.request);
  if (!request) return null;
  return <VoiceCaptureDialog request={request} />;
}
