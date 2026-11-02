import { useCallback, useEffect, useRef, useState } from 'react';
import { voiceApi } from '@/api/client';
import { describeVoiceError, toVoiceError } from '@/lib/voice/errors';
import { IDLE_PLAYBACK, reducePlayback } from '@/lib/voice/playback';
import type { PlaybackEvent, PlaybackState } from '@/lib/voice/playback';
import { VoiceError } from '@/lib/voice/types';
import type { RecordingResponse } from '@/lib/voice/types';
import { useVoiceStore } from '@/stores/voiceStore';
import RecordingsListView from './RecordingsListView';

interface Props {
  snippetId: string;
  scale: number;
}

type ListState = { kind: 'loading' } | { kind: 'loaded'; recordings: RecordingResponse[] } | { kind: 'failed' };

/**
 * Recordings of the snippet open in the inline editor. Loads `GET /api/snippets/{id}/recordings` on open
 * and whenever a take is stored; plays audio fetched with the bearer token into blob URLs (one audio
 * element, one recording at a time; every URL is revoked on delete and unmount); deletes after an
 * inline confirmation. A failed list load hides the section (logged), e.g. against a backend without
 * the endpoint.
 */
function failureMessage(err: unknown): string {
  if (err instanceof VoiceError) return describeVoiceError(err).title;
  if (err instanceof Error) return err.message;
  return String(err);
}

export default function RecordingsSection({ snippetId, scale }: Props) {
  const revision = useVoiceStore((s) => s.recordingsRevision);
  const [list, setList] = useState<ListState>({ kind: 'loading' });
  const [expanded, setExpanded] = useState(false);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [playback, setPlayback] = useState<PlaybackState>(IDLE_PLAYBACK);

  const playbackRef = useRef<PlaybackState>(IDLE_PLAYBACK);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const urls = useRef(new Map<string, string>());
  const mounted = useRef(true);

  useEffect(() => {
    const abort = new AbortController();
    voiceApi.listRecordings(snippetId, abort.signal).then(
      (recordings) => setList({ kind: 'loaded', recordings }),
      (err: unknown) => {
        const error = toVoiceError(err);
        if (error.kind === 'aborted') return;
        console.warn('recordings: list failed', { snippetId, kind: error.kind, status: error.status });
        setList({ kind: 'failed' });
      },
    );
    return () => abort.abort();
  }, [snippetId, revision]);

  // Release the audio element and every blob URL when the editor closes.
  useEffect(() => {
    mounted.current = true;
    const urlMap = urls.current;
    return () => {
      mounted.current = false;
      const audio = audioRef.current;
      if (audio) {
        audio.pause();
        audio.removeAttribute('src');
        audio.load();
      }
      audioRef.current = null;
      for (const url of urlMap.values()) URL.revokeObjectURL(url);
      urlMap.clear();
    };
  }, []);

  const dispatchRef = useRef<(event: PlaybackEvent) => void>(() => undefined);

  const audio = useCallback((): HTMLAudioElement => {
    if (!audioRef.current) {
      const el = new Audio();
      el.onended = () => {
        const current = playbackRef.current;
        if (current.kind !== 'idle') dispatchRef.current({ type: 'ended', id: current.id });
      };
      audioRef.current = el;
    }
    return audioRef.current;
  }, []);

  const audioUrl = useCallback(async (id: string): Promise<string> => {
    const cached = urls.current.get(id);
    if (cached) return cached;
    const blob = await voiceApi.fetchAudio(id);
    const url = URL.createObjectURL(blob);
    if (mounted.current) urls.current.set(id, url);
    else URL.revokeObjectURL(url);
    return url;
  }, []);

  const dispatch = useCallback(
    (event: PlaybackEvent) => {
      if (!mounted.current) return;
      const step = reducePlayback(playbackRef.current, event);
      playbackRef.current = step.state;
      setPlayback(step.state);
      const effect = step.effect;
      const fail = (id: string, err: unknown) => {
        console.warn('recordings: playback failed', { recordingId: id, error: err });
        dispatchRef.current({ type: 'failed', id, message: `Playback failed: ${failureMessage(err)}` });
      };
      switch (effect.kind) {
        case 'none':
          return;
        case 'pause':
        case 'stop':
          audioRef.current?.pause();
          return;
        case 'resume': {
          const id = step.state.kind === 'idle' ? '' : step.state.id;
          audio()
            .play()
            .catch((err: unknown) => fail(id, err));
          return;
        }
        case 'load_and_play': {
          const id = effect.id;
          audioRef.current?.pause();
          audioUrl(id)
            .then(async (url) => {
              const now = playbackRef.current;
              if (!mounted.current || now.kind !== 'loading' || now.id !== id) return;
              const el = audio();
              el.src = url;
              await el.play();
              dispatchRef.current({ type: 'loaded', id });
            })
            .catch((err: unknown) => fail(id, err));
          return;
        }
      }
    },
    [audio, audioUrl],
  );
  dispatchRef.current = dispatch;

  const confirmDelete = (id: string) => {
    setDeletingId(id);
    setDeleteError(null);
    voiceApi.deleteRecording(id).then(
      () => {
        if (!mounted.current) return;
        dispatch({ type: 'removed', id });
        const url = urls.current.get(id);
        if (url) {
          URL.revokeObjectURL(url);
          urls.current.delete(id);
        }
        setList((prev) => (prev.kind === 'loaded' ? { kind: 'loaded', recordings: prev.recordings.filter((r) => r.id !== id) } : prev));
        setConfirmingId(null);
        setDeletingId(null);
      },
      (err: unknown) => {
        if (!mounted.current) return;
        const error = toVoiceError(err);
        console.warn('recordings: delete failed', { recordingId: id, kind: error.kind, status: error.status });
        setDeleteError(error.kind === 'not_found' ? 'That recording was already deleted' : `Delete failed: ${describeVoiceError(error).title}`);
        if (error.kind === 'not_found') {
          setList((prev) => (prev.kind === 'loaded' ? { kind: 'loaded', recordings: prev.recordings.filter((r) => r.id !== id) } : prev));
          setConfirmingId(null);
        }
        setDeletingId(null);
      },
    );
  };

  if (list.kind !== 'loaded') return null;

  return (
    <RecordingsListView
      recordings={list.recordings}
      expanded={expanded}
      playback={playback}
      confirmingId={confirmingId}
      deletingId={deletingId}
      deleteError={deleteError}
      scale={scale}
      onToggleExpanded={() => setExpanded((v) => !v)}
      onTogglePlay={(id) => dispatch({ type: 'toggle', id })}
      onAskDelete={(id) => {
        setDeleteError(null);
        setConfirmingId(id);
      }}
      onConfirmDelete={confirmDelete}
      onCancelDelete={() => setConfirmingId(null)}
    />
  );
}
