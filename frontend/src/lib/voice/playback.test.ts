import { describe, it, expect } from 'vitest';
import { IDLE_PLAYBACK, reducePlayback } from './playback';
import type { PlaybackEvent, PlaybackState } from './playback';

function run(events: PlaybackEvent[], start: PlaybackState = IDLE_PLAYBACK) {
  let state = start;
  const effects: string[] = [];
  for (const e of events) {
    const step = reducePlayback(state, e);
    state = step.state;
    effects.push(step.effect.kind);
  }
  return { state, effects };
}

describe('reducePlayback', () => {
  it('toggle loads then plays; toggling again pauses and resumes', () => {
    const r = run([
      { type: 'toggle', id: 'a' },
      { type: 'loaded', id: 'a' },
      { type: 'toggle', id: 'a' },
      { type: 'toggle', id: 'a' },
    ]);
    expect(r.state).toEqual({ kind: 'playing', id: 'a' });
    expect(r.effects).toEqual(['load_and_play', 'none', 'pause', 'resume']);
  });

  it('switching to another recording stops the first (one active at a time)', () => {
    const r = run([
      { type: 'toggle', id: 'a' },
      { type: 'loaded', id: 'a' },
      { type: 'toggle', id: 'b' },
    ]);
    expect(r.state).toEqual({ kind: 'loading', id: 'b' });
    expect(r.effects[r.effects.length - 1]).toBe('load_and_play');
  });

  it('ignores a late load of a recording that is no longer wanted', () => {
    const r = run([
      { type: 'toggle', id: 'a' },
      { type: 'toggle', id: 'b' },
      { type: 'loaded', id: 'a' },
    ]);
    expect(r.state).toEqual({ kind: 'loading', id: 'b' });
  });

  it('toggling while loading cancels', () => {
    expect(run([{ type: 'toggle', id: 'a' }, { type: 'toggle', id: 'a' }]).state).toEqual(IDLE_PLAYBACK);
  });

  it('failures show on the row and a toggle retries', () => {
    const r = run([
      { type: 'toggle', id: 'a' },
      { type: 'failed', id: 'a', message: 'HTTP 404' },
    ]);
    expect(r.state).toEqual({ kind: 'error', id: 'a', message: 'HTTP 404' });
    expect(reducePlayback(r.state, { type: 'toggle', id: 'a' }).effect.kind).toBe('load_and_play');
  });

  it('ended returns to idle; removing the active recording stops it', () => {
    expect(run([{ type: 'toggle', id: 'a' }, { type: 'loaded', id: 'a' }, { type: 'ended', id: 'a' }]).state).toEqual(IDLE_PLAYBACK);
    const r = run([{ type: 'toggle', id: 'a' }, { type: 'loaded', id: 'a' }, { type: 'removed', id: 'a' }]);
    expect(r.state).toEqual(IDLE_PLAYBACK);
    expect(r.effects[r.effects.length - 1]).toBe('stop');
    expect(reducePlayback({ kind: 'playing', id: 'a' }, { type: 'removed', id: 'b' }).effect.kind).toBe('none');
  });
});
