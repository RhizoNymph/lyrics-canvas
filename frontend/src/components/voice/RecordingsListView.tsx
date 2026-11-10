import type { CSSProperties } from 'react';
import { formatDuration, formatRecordedAt, transcriptPreview } from '@/lib/voice/format';
import type { PlaybackState } from '@/lib/voice/playback';
import { verificationOf } from '@/lib/voice/recordingView';
import type { RecordingResponse } from '@/lib/voice/types';

export interface RecordingsListViewProps {
  recordings: readonly RecordingResponse[];
  expanded: boolean;
  playback: PlaybackState;
  /** Recording awaiting delete confirmation, or null. */
  confirmingId: string | null;
  /** Recording being deleted, or null. */
  deletingId: string | null;
  deleteError: string | null;
  /** Editor scale (canvas zoom x card scale); 1 in list rows. */
  scale: number;
  onToggleExpanded: () => void;
  onTogglePlay: (id: string) => void;
  onAskDelete: (id: string) => void;
  onConfirmDelete: (id: string) => void;
  onCancelDelete: () => void;
  /** Open the check transcript view for a recording; omitted hides the action. */
  onCheck?: (id: string) => void;
  locale?: string;
}

/**
 * The editor's compact recordings section: a one-line toggle "Recordings (n)", and when expanded one
 * row per take (date, duration, transcript preview, Checked / Not checked badge, Check transcript,
 * play/pause, delete with inline confirm).
 * Renders nothing when the snippet has no recordings, so the editor's layout is unchanged.
 */
export default function RecordingsListView(props: RecordingsListViewProps) {
  const { recordings, expanded, playback, confirmingId, deletingId, deleteError, scale: s } = props;
  if (recordings.length === 0) return null;

  const small: CSSProperties = {
    background: 'rgba(255,255,255,0.06)',
    color: '#c0c0d8',
    border: '1px solid #3a3a5a',
    borderRadius: 3,
    fontSize: 10 * s,
    padding: `${1 * s}px ${6 * s}px`,
    cursor: 'pointer',
    flexShrink: 0,
  };

  return (
    <div
      data-recordings-section
      style={{ borderTop: '1px solid #2a2a4a', padding: `${4 * s}px ${8 * s}px`, fontFamily: 'monospace', fontSize: 10 * s, color: '#8a8aaa' }}
    >
      <button
        type="button"
        aria-expanded={expanded}
        onClick={props.onToggleExpanded}
        style={{ background: 'none', border: 'none', color: '#a0a0c0', fontFamily: 'inherit', fontSize: 10 * s, padding: 0, cursor: 'pointer' }}
      >
        {`${expanded ? '▾' : '▸'} Recordings (${recordings.length})`}
      </button>
      {expanded && (
        <ul style={{ listStyle: 'none', margin: `${4 * s}px 0 0`, padding: 0, display: 'flex', flexDirection: 'column', gap: 3 * s }}>
          {recordings.map((r) => {
            const active = playback.kind !== 'idle' && playback.id === r.id ? playback : null;
            const playing = active?.kind === 'playing';
            const loading = active?.kind === 'loading';
            const confirming = confirmingId === r.id;
            const deleting = deletingId === r.id;
            const checked = verificationOf(r).kind === 'checked';
            return (
              <li key={r.id} style={{ display: 'flex', alignItems: 'center', gap: 6 * s, minWidth: 0 }}>
                <button
                  type="button"
                  onClick={() => props.onTogglePlay(r.id)}
                  aria-label={playing ? 'Pause recording' : 'Play recording'}
                  aria-pressed={playing}
                  style={{ ...small, minWidth: 22 * s }}
                >
                  {loading ? '…' : playing ? '❚❚' : '▶︎'}
                </button>
                <span style={{ whiteSpace: 'nowrap', color: '#a0a0c0' }}>{formatRecordedAt(r.created_at, props.locale)}</span>
                <span style={{ whiteSpace: 'nowrap' }}>{formatDuration(r.duration_ms)}</span>
                <span
                  title={r.transcript}
                  style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: '#7a7a9a' }}
                >
                  {active?.kind === 'error' ? <span style={{ color: '#f87171' }}>{active.message}</span> : transcriptPreview(r.transcript)}
                </span>
                <span
                  data-check-badge={checked ? 'checked' : 'unchecked'}
                  title={checked ? 'The transcript of this take was checked' : 'The transcript of this take was not checked yet'}
                  style={{
                    flexShrink: 0,
                    whiteSpace: 'nowrap',
                    borderRadius: 3,
                    padding: `0 ${4 * s}px`,
                    fontSize: 9 * s,
                    color: checked ? '#86efac' : '#7a7a9a',
                    border: `1px solid ${checked ? '#166534' : '#3a3a5a'}`,
                  }}
                >
                  {checked ? 'Checked' : 'Not checked'}
                </span>
                {props.onCheck && (
                  <button
                    type="button"
                    onClick={() => props.onCheck?.(r.id)}
                    aria-label="Check transcript"
                    title="Check transcript: listen and correct what Whisper heard"
                    style={small}
                  >
                    Check
                  </button>
                )}
                {confirming ? (
                  <span role="group" aria-label="Confirm delete" style={{ display: 'flex', gap: 4 * s, alignItems: 'center', flexShrink: 0 }}>
                    <span style={{ color: '#fca5a5' }}>Delete?</span>
                    <button type="button" disabled={deleting} onClick={() => props.onConfirmDelete(r.id)} style={{ ...small, color: '#fca5a5' }}>
                      {deleting ? '…' : 'Yes'}
                    </button>
                    <button type="button" disabled={deleting} onClick={props.onCancelDelete} style={small}>
                      No
                    </button>
                  </span>
                ) : (
                  <button type="button" onClick={() => props.onAskDelete(r.id)} aria-label="Delete recording" style={small}>
                    ✕
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {deleteError && (
        <div role="alert" style={{ color: '#f87171', marginTop: 2 * s }}>
          {deleteError}
        </div>
      )}
    </div>
  );
}
