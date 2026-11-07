import { formatDuration } from '@/lib/voice/format';
import type { PlayerState } from './useRecordingPlayer';

export interface AudioPlayerBarProps {
  state: PlayerState;
  onToggle: () => void;
  onSeek: (ms: number) => void;
  /** Shortcut hint for the play button's title, e.g. "Ctrl+Space". */
  toggleShortcut: string;
}

const STEP_MS = 100;

/** Play/pause, a scrubber (range input over ms) and "current / total". */
export default function AudioPlayerBar({ state, onToggle, onSeek, toggleShortcut }: AudioPlayerBarProps) {
  if (state.kind === 'failed') {
    return (
      <div role="alert" style={{ color: '#f87171', fontSize: 13 }}>
        {state.message}
      </div>
    );
  }
  const ready = state.kind === 'ready';
  const playing = ready && state.playing;
  const position = ready ? Math.min(state.positionMs, state.durationMs) : 0;
  const duration = ready ? state.durationMs : 0;
  return (
    <div data-check-player style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
      <button
        type="button"
        disabled={!ready}
        onClick={onToggle}
        aria-label={playing ? 'Pause' : 'Play'}
        aria-pressed={playing}
        title={`${playing ? 'Pause' : 'Play'} (${toggleShortcut})`}
        style={{
          minWidth: 40,
          height: 32,
          borderRadius: 6,
          border: '1px solid #3a3a5a',
          background: '#2a2a4a',
          color: '#e0e0e0',
          cursor: ready ? 'pointer' : 'default',
          fontSize: 14,
        }}
      >
        {!ready ? '…' : playing ? '❚❚' : '▶︎'}
      </button>
      <input
        type="range"
        aria-label="Position"
        min={0}
        max={Math.max(1, Math.round(duration))}
        step={STEP_MS}
        value={Math.round(position)}
        disabled={!ready}
        onChange={(e) => onSeek(Number(e.target.value))}
        style={{ flex: 1, minWidth: 0, accentColor: '#6366f1' }}
      />
      <span aria-label="Time" style={{ fontVariantNumeric: 'tabular-nums', fontSize: 12, color: '#a0a0c0', whiteSpace: 'nowrap' }}>
        {`${formatDuration(position)} / ${formatDuration(duration)}`}
      </span>
    </div>
  );
}
