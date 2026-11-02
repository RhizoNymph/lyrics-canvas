/**
 * Play/pause state for the editor's recordings list: at most one recording is active. The component
 * owns the audio element and the blob URLs and performs the returned effect.
 */
export type PlaybackState =
  | { kind: 'idle' }
  | { kind: 'loading'; id: string }
  | { kind: 'playing'; id: string }
  | { kind: 'paused'; id: string }
  | { kind: 'error'; id: string; message: string };

export const IDLE_PLAYBACK: PlaybackState = { kind: 'idle' };

export type PlaybackEvent =
  /** The play/pause button of recording `id`. */
  | { type: 'toggle'; id: string }
  | { type: 'loaded'; id: string }
  | { type: 'failed'; id: string; message: string }
  | { type: 'ended'; id: string }
  /** Recording `id` was deleted. */
  | { type: 'removed'; id: string };

export type PlaybackEffect =
  | { kind: 'none' }
  /** Stop whatever plays, fetch (or reuse) the audio of `id`, then play it. */
  | { kind: 'load_and_play'; id: string }
  | { kind: 'pause' }
  | { kind: 'resume' }
  | { kind: 'stop' };

export interface PlaybackStep {
  state: PlaybackState;
  effect: PlaybackEffect;
}

const NONE: PlaybackEffect = { kind: 'none' };

function activeId(state: PlaybackState): string | null {
  return state.kind === 'idle' ? null : state.id;
}

export function reducePlayback(state: PlaybackState, event: PlaybackEvent): PlaybackStep {
  const current = activeId(state);
  switch (event.type) {
    case 'toggle': {
      if (current !== event.id || state.kind === 'error') {
        return { state: { kind: 'loading', id: event.id }, effect: { kind: 'load_and_play', id: event.id } };
      }
      if (state.kind === 'loading') return { state: IDLE_PLAYBACK, effect: { kind: 'stop' } };
      if (state.kind === 'playing') return { state: { kind: 'paused', id: event.id }, effect: { kind: 'pause' } };
      if (state.kind === 'paused') return { state: { kind: 'playing', id: event.id }, effect: { kind: 'resume' } };
      return { state, effect: NONE };
    }
    case 'loaded':
      return state.kind === 'loading' && state.id === event.id
        ? { state: { kind: 'playing', id: event.id }, effect: NONE }
        : { state, effect: NONE };
    case 'failed':
      return current === event.id && state.kind !== 'error'
        ? { state: { kind: 'error', id: event.id, message: event.message }, effect: NONE }
        : { state, effect: NONE };
    case 'ended':
      return current === event.id && (state.kind === 'playing' || state.kind === 'paused')
        ? { state: IDLE_PLAYBACK, effect: NONE }
        : { state, effect: NONE };
    case 'removed':
      return current === event.id ? { state: IDLE_PLAYBACK, effect: { kind: 'stop' } } : { state, effect: NONE };
  }
}
