import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, RefObject } from 'react';
import { formatDatasetStats } from '@/lib/voice/dataset';
import { describeVoiceError } from '@/lib/voice/errors';
import type { VoiceDatasetStats, VoiceError } from '@/lib/voice/types';

export type StatsState = { kind: 'loading' } | { kind: 'loaded'; stats: VoiceDatasetStats } | { kind: 'failed'; error: VoiceError };

export type DownloadState =
  | { kind: 'idle' }
  | { kind: 'downloading' }
  | { kind: 'done'; filename: string }
  | { kind: 'failed'; error: VoiceError };

export interface VoiceDatasetViewProps {
  stats: StatsState;
  download: DownloadState;
  includeUnverified: boolean;
  surfaceRef?: RefObject<HTMLDivElement>;
  onIncludeUnverifiedChange: (include: boolean) => void;
  onDownload: () => void;
  onClose: () => void;
  onKeyDown?: (e: ReactKeyboardEvent<HTMLDivElement>) => void;
}

const TITLE_ID = 'voice-dataset-title';

const button: CSSProperties = {
  border: '1px solid #3a3a5a',
  borderRadius: 6,
  padding: '8px 16px',
  fontSize: 13,
  cursor: 'pointer',
  background: '#2a2a4a',
  color: '#e0e0e0',
};
const primary: CSSProperties = { ...button, background: '#6366f1', borderColor: '#6366f1', color: '#fff' };

/** The "Voice dataset" dialog of the canvas list page: stats line, include-unchecked switch, zip download. */
export default function VoiceDatasetView(props: VoiceDatasetViewProps) {
  const { stats, download } = props;
  const downloading = download.kind === 'downloading';
  const emptyExport = stats.kind === 'loaded' && stats.stats.verified === 0 && !props.includeUnverified;

  return (
    <div
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) props.onClose();
      }}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 3000,
        background: 'rgba(10, 10, 28, 0.8)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
      }}
    >
      <div
        ref={props.surfaceRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={TITLE_ID}
        tabIndex={-1}
        onKeyDown={props.onKeyDown}
        style={{
          width: 'min(460px, 100%)',
          background: '#1c1c38',
          border: '1px solid #3a3a5a',
          borderRadius: 10,
          padding: 20,
          boxSizing: 'border-box',
          color: '#e0e0e0',
          display: 'flex',
          flexDirection: 'column',
          gap: 14,
          outline: 'none',
        }}
      >
        <h2 id={TITLE_ID} style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>
          Voice dataset
        </h2>
        <p style={{ margin: 0, fontSize: 12, color: '#8a8aaa', lineHeight: 1.5 }}>
          Your voice takes with their checked transcripts, for fine-tuning speech recognition. The zip holds the original
          audio and a JSONL metadata file.
        </p>

        {stats.kind === 'loading' && <div style={{ fontSize: 13, color: '#a0a0c0' }}>Loading…</div>}
        {stats.kind === 'loaded' && (
          <div data-dataset-stats style={{ fontSize: 14 }}>
            {formatDatasetStats(stats.stats)}
          </div>
        )}
        {stats.kind === 'failed' && (
          <div role="alert" style={{ fontSize: 13, color: '#f87171' }}>
            {`Stats unavailable: ${describeVoiceError(stats.error).title}`}
          </div>
        )}

        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: '#c0c0d8' }}>
          <input
            type="checkbox"
            checked={props.includeUnverified}
            disabled={downloading}
            onChange={(e) => props.onIncludeUnverifiedChange(e.target.checked)}
          />
          Include unchecked takes
        </label>
        {emptyExport && (
          <div style={{ fontSize: 12, color: '#fbbf24' }}>No checked takes yet: the zip will hold no recordings.</div>
        )}

        {download.kind === 'failed' && (
          <div role="alert" style={{ fontSize: 13, color: '#f87171' }}>
            {describeVoiceError(download.error).title}
            {describeVoiceError(download.error).hint && (
              <div style={{ fontSize: 12, color: '#c0c0d8' }}>{describeVoiceError(download.error).hint}</div>
            )}
          </div>
        )}
        {download.kind === 'done' && (
          <div role="status" style={{ fontSize: 12, color: '#86efac' }}>{`Saved ${download.filename}`}</div>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <button type="button" style={button} onClick={props.onClose}>
            Close
          </button>
          <button type="button" style={primary} disabled={downloading} onClick={props.onDownload}>
            {downloading ? 'Preparing…' : 'Download dataset (.zip)'}
          </button>
        </div>
      </div>
    </div>
  );
}
