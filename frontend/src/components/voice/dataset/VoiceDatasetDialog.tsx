import { useCallback, useEffect, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { voiceApi } from '@/api/client';
import { toVoiceError } from '@/lib/voice/errors';
import { useFocusTrap } from '../useFocusTrap';
import { saveBlobAs } from './saveBlob';
import VoiceDatasetView from './VoiceDatasetView';
import type { DownloadState, StatsState } from './VoiceDatasetView';

/**
 * Store-free dialog: loads `GET /api/recordings/stats` on open and downloads
 * `GET /api/recordings/export` with the bearer token into a blob, saved under the server's file name.
 * Closing aborts both requests.
 */
export default function VoiceDatasetDialog({ onClose }: { onClose: () => void }) {
  const [stats, setStats] = useState<StatsState>({ kind: 'loading' });
  const [download, setDownload] = useState<DownloadState>({ kind: 'idle' });
  const [includeUnverified, setIncludeUnverified] = useState(false);
  const surfaceRef = useRef<HTMLDivElement>(null);
  const onTrapKeyDown = useFocusTrap(surfaceRef);
  const exportAbort = useRef<AbortController | null>(null);

  useEffect(() => {
    const abort = new AbortController();
    voiceApi.datasetStats(abort.signal).then(
      (s) => setStats({ kind: 'loaded', stats: s }),
      (err: unknown) => {
        const error = toVoiceError(err);
        if (error.kind === 'aborted') return;
        console.warn('voice dataset: stats failed', { kind: error.kind, status: error.status });
        setStats({ kind: 'failed', error });
      },
    );
    return () => {
      abort.abort();
      exportAbort.current?.abort();
    };
  }, []);

  const onDownload = useCallback(() => {
    if (exportAbort.current) return;
    const abort = new AbortController();
    exportAbort.current = abort;
    setDownload({ kind: 'downloading' });
    voiceApi.exportDataset({ includeUnverified }, abort.signal).then(
      ({ blob, filename }) => {
        exportAbort.current = null;
        if (abort.signal.aborted) return;
        saveBlobAs(blob, filename);
        console.info('voice dataset: exported', { filename, bytes: blob.size, includeUnverified });
        setDownload({ kind: 'done', filename });
      },
      (err: unknown) => {
        exportAbort.current = null;
        const error = toVoiceError(err);
        if (error.kind === 'aborted') return;
        console.warn('voice dataset: export failed', { kind: error.kind, status: error.status, includeUnverified });
        setDownload({ kind: 'failed', error });
      },
    );
  }, [includeUnverified]);

  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Tab') onTrapKeyDown(e);
    else if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
    }
  };

  return (
    <VoiceDatasetView
      stats={stats}
      download={download}
      includeUnverified={includeUnverified}
      surfaceRef={surfaceRef}
      onIncludeUnverifiedChange={(v) => {
        setIncludeUnverified(v);
        setDownload({ kind: 'idle' });
      }}
      onDownload={onDownload}
      onClose={onClose}
      onKeyDown={onKeyDown}
    />
  );
}
