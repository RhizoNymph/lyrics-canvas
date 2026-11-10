import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, RefObject } from 'react';
import { PLAYBACK_TOGGLE_LABEL } from '@/lib/voice/checkKeys';
import { CHECK_HINT, checkErrorMessage } from '@/lib/voice/checkTranscript';
import type { CheckState } from '@/lib/voice/checkTranscript';
import { describeVoiceError } from '@/lib/voice/errors';
import { formatDuration, formatRecordedAt } from '@/lib/voice/format';
import type { TranscriptReference } from '@/lib/voice/recordingView';
import type { RecordingResponse } from '@/lib/voice/types';
import AudioPlayerBar from './AudioPlayerBar';
import TranscriptReferenceView from './TranscriptReferenceView';
import type { PlayerState } from './useRecordingPlayer';

export interface CheckTranscriptViewProps {
  recording: RecordingResponse;
  checked: boolean;
  reference: TranscriptReference;
  activeWord: number | null;
  player: PlayerState;
  text: string;
  check: CheckState;
  surfaceRef?: RefObject<HTMLDivElement>;
  textareaRef?: RefObject<HTMLTextAreaElement>;
  onTextChange: (text: string) => void;
  onWord: (index: number) => void;
  onTogglePlay: () => void;
  onSeek: (ms: number) => void;
  onSave: () => void;
  onUncheck: () => void;
  onCancel: () => void;
  onDiscard: () => void;
  onKeepEditing: () => void;
  onKeyDown?: (e: ReactKeyboardEvent<HTMLDivElement>) => void;
  locale?: string;
}

const TITLE_ID = 'check-transcript-title';
const HINT_ID = 'check-transcript-hint';

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

/**
 * The check transcript modal, presentational: player, read-only Whisper reference, correction field
 * with the contract hint, actions with inline typed errors and the dirty-cancel confirm.
 */
export default function CheckTranscriptView(props: CheckTranscriptViewProps) {
  const { recording, checked, check } = props;
  const busy = check.kind === 'saving' || check.kind === 'unchecking';
  const confirming = check.kind === 'confirm_discard';
  const failure = check.kind === 'failed' ? check.error : null;
  const hint = failure?.kind === 'server' ? describeVoiceError(failure.error).hint : null;

  return (
    <div
      ref={props.surfaceRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby={TITLE_ID}
      tabIndex={-1}
      data-keep-editor-open
      onKeyDown={props.onKeyDown}
      onClick={(e) => e.stopPropagation()}
      onDoubleClick={(e) => e.stopPropagation()}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 3000,
        background: 'rgba(10, 10, 28, 0.85)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
        boxSizing: 'border-box',
        outline: 'none',
      }}
    >
      <div
        style={{
          width: 'min(640px, 100%)',
          maxHeight: '100%',
          overflowY: 'auto',
          background: '#1c1c38',
          border: '1px solid #3a3a5a',
          borderRadius: 10,
          padding: 20,
          boxSizing: 'border-box',
          color: '#e0e0e0',
          display: 'flex',
          flexDirection: 'column',
          gap: 14,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
          <h2 id={TITLE_ID} style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>
            Check transcript
          </h2>
          <span style={{ fontSize: 12, color: '#8a8aaa' }}>
            {`${formatRecordedAt(recording.created_at, props.locale)} · ${formatDuration(recording.duration_ms)}`}
          </span>
          <span data-check-badge={checked ? 'checked' : 'unchecked'} style={{ fontSize: 11, color: checked ? '#86efac' : '#8a8aaa' }}>
            {checked ? 'Checked' : 'Not checked'}
          </span>
        </div>

        <AudioPlayerBar state={props.player} onToggle={props.onTogglePlay} onSeek={props.onSeek} toggleShortcut={PLAYBACK_TOGGLE_LABEL} />

        <section aria-label="What Whisper heard" style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <div style={{ fontSize: 12, color: '#a0a0c0' }}>
            {props.reference.kind === 'timed' ? 'What Whisper heard (tap a word to jump there)' : 'What Whisper heard'}
          </div>
          <TranscriptReferenceView reference={props.reference} activeWord={props.activeWord} onWord={props.onWord} />
        </section>

        <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 12, color: '#a0a0c0' }}>
          What was actually said
          <textarea
            ref={props.textareaRef}
            value={props.text}
            onChange={(e) => props.onTextChange(e.target.value)}
            aria-describedby={HINT_ID}
            rows={Math.min(12, Math.max(4, props.text.split('\n').length + 1))}
            spellCheck={false}
            readOnly={busy}
            style={{
              background: '#12122a',
              color: '#e0e0e0',
              border: '1px solid #3a3a5a',
              borderRadius: 6,
              padding: '8px 10px',
              fontSize: 14,
              lineHeight: 1.5,
              fontFamily: 'inherit',
              resize: 'vertical',
            }}
          />
        </label>
        <div id={HINT_ID} style={{ fontSize: 12, color: '#8a8aaa', marginTop: -8 }}>
          {CHECK_HINT}
        </div>

        {failure && (
          <div role="alert" style={{ color: '#f87171', fontSize: 13 }}>
            {checkErrorMessage(failure)}
            {hint && <div style={{ color: '#c0c0d8', fontSize: 12 }}>{hint}</div>}
          </div>
        )}

        {confirming ? (
          <div role="group" aria-label="Discard changes" style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 13, color: '#fca5a5', flex: 1 }}>Discard your changes to this transcript?</span>
            <button type="button" style={{ ...button, color: '#fca5a5' }} onClick={props.onDiscard}>
              Discard
            </button>
            <button type="button" style={button} onClick={props.onKeepEditing}>
              Keep editing
            </button>
          </div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 11, color: '#6a6a8a', flex: 1, minWidth: 160 }}>
              {`Ctrl/Cmd+Enter save · ${PLAYBACK_TOGGLE_LABEL} play/pause · Alt+←/→ 3 s`}
            </span>
            {checked && (
              <button type="button" style={button} disabled={busy} onClick={props.onUncheck}>
                {check.kind === 'unchecking' ? 'Unchecking…' : 'Mark unchecked'}
              </button>
            )}
            <button type="button" style={button} disabled={busy} onClick={props.onCancel}>
              Cancel
            </button>
            <button type="button" style={primary} disabled={busy} onClick={props.onSave}>
              {check.kind === 'saving' ? 'Saving…' : 'Save as checked'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
