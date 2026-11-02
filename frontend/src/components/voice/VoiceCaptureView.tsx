import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent, RefObject } from 'react';
import { describeCaptureError, describeVoiceError } from '@/lib/voice/errors';
import { formatDuration } from '@/lib/voice/format';
import { modalKeyAction } from '@/lib/voice/keys';
import { lineNumber } from '@/lib/voice/recorder';
import type { SessionSnapshot } from '@/lib/voice/session';
import { SILENCE_CHOICES, silenceLabel } from '@/lib/voice/silenceSetting';
import type { SilenceSeconds } from '@/lib/voice/silenceSetting';
import type { CaptureTarget } from '@/lib/voice/target';

export interface VoiceCaptureViewProps {
  snapshot: SessionSnapshot;
  target: CaptureTarget;
  silenceSeconds: SilenceSeconds;
  surfaceRef?: RefObject<HTMLDivElement>;
  onTap: () => void;
  onStop: () => void;
  onCancel: () => void;
  onRetry: () => void;
  onDiscard: () => void;
  onSaveAudio: () => void;
  onSilenceChange: (seconds: SilenceSeconds) => void;
  /** Tab handling for the focus trap. */
  onTrapKeyDown?: (e: ReactKeyboardEvent<HTMLDivElement>) => void;
}

const TITLE_ID = 'voice-capture-title';
const SPIN_KEYFRAMES = '@keyframes lc-voice-spin { to { transform: rotate(360deg); } }';

const button: CSSProperties = {
  border: '1px solid #3a3a5a',
  borderRadius: 8,
  padding: '10px 20px',
  fontSize: 14,
  cursor: 'pointer',
  background: '#2a2a4a',
  color: '#e0e0e0',
  minWidth: 96,
};
const primary: CSSProperties = { ...button, background: '#6366f1', borderColor: '#6366f1', color: '#fff' };
const danger: CSSProperties = { ...button, background: '#ef4444', borderColor: '#ef4444', color: '#fff' };

/** Stops a control's pointerdown from reaching the tap surface (buttons are never taps). */
function notATap(e: ReactPointerEvent) {
  e.stopPropagation();
}

function Spinner() {
  return (
    <span
      aria-hidden
      style={{
        display: 'inline-block',
        width: 28,
        height: 28,
        border: '3px solid rgba(255,255,255,0.2)',
        borderTopColor: '#a5b4fc',
        borderRadius: '50%',
        animation: 'lc-voice-spin 0.9s linear infinite',
      }}
    />
  );
}

function LevelMeter({ level }: { level: number }) {
  const pct = Math.round(level * 100);
  return (
    <div
      role="meter"
      aria-label="Input level"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={pct}
      style={{ width: 240, height: 8, background: '#2a2a4a', borderRadius: 4, overflow: 'hidden' }}
    >
      <div style={{ width: `${pct}%`, height: '100%', background: pct > 90 ? '#f59e0b' : '#22c55e', transition: 'width 80ms linear' }} />
    </div>
  );
}

/**
 * The recorder modal, presentational. While recording the whole overlay is the tap target:
 * pointerdown (primary button) or Space / Enter = next line, a second tap within 300 ms = stop.
 * Stop, Cancel and the silence select never count as taps. Escape cancels.
 */
export default function VoiceCaptureView(props: VoiceCaptureViewProps) {
  const { snapshot, target, silenceSeconds, surfaceRef } = props;
  const state = snapshot.recorder;
  const recording = state.kind === 'recording';

  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (!recording) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    // Keep focus on the surface (so Space/Enter keep working) and avoid text selection on fast taps.
    e.preventDefault();
    surfaceRef?.current?.focus();
    props.onTap();
  };

  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    // Keys never leak to the page shortcuts (Delete, Ctrl+Z, ...) while the modal is open.
    e.stopPropagation();
    if (e.key === 'Tab') {
      props.onTrapKeyDown?.(e);
      return;
    }
    const tag = e.target instanceof HTMLElement ? e.target.tagName : '';
    const action = modalKeyAction(e, tag);
    if (action === 'cancel') {
      e.preventDefault();
      props.onCancel();
    } else if (action === 'tap' && recording) {
      e.preventDefault();
      props.onTap();
    }
  };

  const targetLabel = target.kind === 'append' ? 'Appending to the open snippet' : 'New snippet';

  let body: JSX.Element;
  switch (state.kind) {
    case 'idle':
    case 'requesting_permission':
      body = (
        <>
          <Spinner />
          <div style={{ fontSize: 15 }}>Waiting for the microphone…</div>
          <div style={{ fontSize: 12, color: '#8a8aaa' }}>Allow microphone access if the browser asks.</div>
        </>
      );
      break;
    case 'recording':
      body = (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: '#fca5a5' }}>
            <span aria-hidden style={{ width: 10, height: 10, borderRadius: '50%', background: '#ef4444' }} />
            Recording
          </div>
          <div style={{ fontSize: 48, fontVariantNumeric: 'tabular-nums', fontWeight: 300 }} aria-label="Elapsed time">
            {formatDuration(snapshot.elapsedMs)}
          </div>
          <div aria-live="polite" style={{ fontSize: 22, color: snapshot.tapPending ? '#a5b4fc' : '#e0e0e0' }}>
            {`Line ${lineNumber(state, snapshot.tapPending)}`}
          </div>
          <LevelMeter level={snapshot.level} />
          <div style={{ fontSize: 12, color: '#8a8aaa', textAlign: 'center', lineHeight: 1.6 }}>
            Tap anywhere for a new line · double-tap to stop
            <br />
            Space / Enter: new line · Esc: cancel
          </div>
        </>
      );
      break;
    case 'stopping':
    case 'uploading':
      body = (
        <>
          <Spinner />
          <div role="status" style={{ fontSize: 15 }}>
            {state.kind === 'stopping' ? 'Finishing the recording…' : 'Uploading and transcribing…'}
          </div>
          {state.kind === 'uploading' && state.attempt > 1 && (
            <div style={{ fontSize: 12, color: '#8a8aaa' }}>{`Attempt ${state.attempt}`}</div>
          )}
        </>
      );
      break;
    case 'done':
      body = <div role="status">Done</div>;
      break;
    case 'capture_failed':
    case 'upload_failed': {
      const d = state.kind === 'capture_failed' ? describeCaptureError(state.error) : describeVoiceError(state.error);
      body = (
        <div role="alert" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, maxWidth: 420, textAlign: 'center' }}>
          <div style={{ fontSize: 17, color: '#fca5a5' }}>{d.title}</div>
          {d.hint && <div style={{ fontSize: 13, color: '#c0c0d8' }}>{d.hint}</div>}
          {state.error.message && state.error.message !== d.title && (
            <div style={{ fontSize: 11, color: '#7a7a9a' }}>{state.error.message}</div>
          )}
          {state.kind === 'upload_failed' && (
            <div style={{ fontSize: 12, color: '#8a8aaa' }}>{`The recording (${formatDuration(state.take.durationMs)}) is kept until you discard it.`}</div>
          )}
          <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap', justifyContent: 'center' }}>
            {d.retryable && (
              <button type="button" style={primary} onPointerDown={notATap} onClick={props.onRetry}>
                Retry
              </button>
            )}
            {state.kind === 'upload_failed' && (
              <button type="button" style={button} onPointerDown={notATap} onClick={props.onSaveAudio}>
                Save audio
              </button>
            )}
            <button type="button" style={button} onPointerDown={notATap} onClick={props.onDiscard}>
              Discard
            </button>
          </div>
        </div>
      );
      break;
    }
  }

  const showControls = state.kind !== 'capture_failed' && state.kind !== 'upload_failed' && state.kind !== 'done';

  return (
    <div
      ref={surfaceRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby={TITLE_ID}
      tabIndex={-1}
      data-keep-editor-open
      onPointerDown={onPointerDown}
      onKeyDown={onKeyDown}
      onClick={(e) => e.stopPropagation()}
      onContextMenu={(e) => e.preventDefault()}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 3000,
        background: 'rgba(10, 10, 28, 0.94)',
        color: '#e0e0e0',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        padding: 16,
        boxSizing: 'border-box',
        outline: 'none',
        userSelect: 'none',
        WebkitUserSelect: 'none',
        touchAction: 'manipulation',
        cursor: recording ? 'pointer' : 'default',
      }}
    >
      <style>{SPIN_KEYFRAMES}</style>
      <h2 id={TITLE_ID} style={{ margin: 0, fontSize: 14, fontWeight: 600, color: '#a0a0c0', letterSpacing: 0.3 }}>
        Voice capture · {targetLabel}
      </h2>
      {body}
      {showControls && (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12, marginTop: 8 }}>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" style={danger} disabled={!recording} onPointerDown={notATap} onClick={props.onStop}>
              Stop
            </button>
            <button type="button" style={button} onPointerDown={notATap} onClick={props.onCancel}>
              Cancel
            </button>
          </div>
          <label onPointerDown={notATap} style={{ fontSize: 12, color: '#a0a0c0', display: 'flex', alignItems: 'center', gap: 6 }}>
            Auto-stop after silence
            <select
              value={silenceSeconds}
              onChange={(e) => props.onSilenceChange(Number(e.target.value) as SilenceSeconds)}
              style={{ background: '#2a2a4a', color: '#e0e0e0', border: '1px solid #3a3a5a', borderRadius: 4, padding: '3px 6px' }}
            >
              {SILENCE_CHOICES.map((s) => (
                <option key={s} value={s}>
                  {silenceLabel(s)}
                </option>
              ))}
            </select>
          </label>
        </div>
      )}
    </div>
  );
}
