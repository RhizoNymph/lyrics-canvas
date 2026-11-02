import { describe, it, expect } from 'vitest';
import { INITIAL_VAD, MIN_SPEECH_MS, MIN_SPEECH_RMS, speechThreshold, stepVad } from './vad';
import type { VadState } from './vad';

const TICK = 100;

/** Feed `rms` every TICK ms for `ms`, starting at `t`; returns the final state, time and the stop time. */
function feed(state: VadState, t: number, rms: number, ms: number, silenceMs: number) {
  let s = state;
  let at = t;
  let stoppedAt: number | null = null;
  for (let elapsed = 0; elapsed < ms; elapsed += TICK) {
    const out = stepVad(s, { rms, at }, silenceMs);
    s = out.state;
    if (out.stop && stoppedAt === null) stoppedAt = at;
    at += TICK;
  }
  return { state: s, at, stoppedAt };
}

describe('stepVad', () => {
  it('never stops when the setting is off (0)', () => {
    let r = feed(INITIAL_VAD, 0, 0.005, 1000, 0);
    r = feed(r.state, r.at, 0.2, 1000, 0);
    r = feed(r.state, r.at, 0.005, 60_000, 0);
    expect(r.stoppedAt).toBeNull();
  });

  it('does not arm before speech was heard: a silent start never stops', () => {
    const r = feed(INITIAL_VAD, 0, 0.004, 30_000, 2000);
    expect(r.stoppedAt).toBeNull();
    expect(r.state.speechHeard).toBe(false);
  });

  it('stops after N seconds of continuous silence following speech', () => {
    let r = feed(INITIAL_VAD, 0, 0.004, 1000, 3000); // room noise
    r = feed(r.state, r.at, 0.15, 2000, 3000); // speech
    expect(r.state.speechHeard).toBe(true);
    const silenceStart = r.at;
    r = feed(r.state, r.at, 0.004, 5000, 3000);
    expect(r.stoppedAt).not.toBeNull();
    expect(r.stoppedAt! - silenceStart).toBe(3000);
  });

  it('speech in between resets the silence timer', () => {
    let r = feed(INITIAL_VAD, 0, 0.004, 1000, 2000);
    r = feed(r.state, r.at, 0.15, 1000, 2000);
    r = feed(r.state, r.at, 0.004, 1500, 2000);
    expect(r.stoppedAt).toBeNull();
    r = feed(r.state, r.at, 0.15, 300, 2000);
    const silenceStart = r.at;
    r = feed(r.state, r.at, 0.004, 4000, 2000);
    expect(r.stoppedAt! - silenceStart).toBe(2000);
  });

  it('a short click (one tick) does not count as speech', () => {
    let r = feed(INITIAL_VAD, 0, 0.004, 1000, 2000);
    r = feed(r.state, r.at, 0.5, TICK, 2000);
    r = feed(r.state, r.at, 0.004, 10_000, 2000);
    expect(r.state.speechHeard).toBe(false);
    expect(r.stoppedAt).toBeNull();
    expect(MIN_SPEECH_MS).toBeGreaterThan(TICK);
  });

  it('adapts to a noisy room: speech must clear the higher floor, and the noise counts as silence', () => {
    let r = feed(INITIAL_VAD, 0, 0.03, 3000, 2000); // noisy room, above the absolute minimum
    expect(r.state.noiseFloor).toBeGreaterThan(0.02);
    expect(speechThreshold(r.state.noiseFloor)).toBeGreaterThan(0.03);
    r = feed(r.state, r.at, 0.04, 2000, 2000); // a bit louder than the room: still not speech
    expect(r.state.speechHeard).toBe(false);
    r = feed(r.state, r.at, 0.3, 1000, 2000); // real speech
    expect(r.state.speechHeard).toBe(true);
    const silenceStart = r.at;
    r = feed(r.state, r.at, 0.03, 5000, 2000); // back to room noise
    expect(r.stoppedAt! - silenceStart).toBe(2000);
  });

  it('the floor drops quickly when the room gets quieter', () => {
    let r = feed(INITIAL_VAD, 0, 0.05, 2000, 2000);
    const high = r.state.noiseFloor!;
    r = feed(r.state, r.at, 0.002, 1000, 2000);
    expect(r.state.noiseFloor!).toBeLessThan(high / 4);
  });

  it('arms when speech starts on the very first sample (the floor is not seeded from speech)', () => {
    // Seen in Chromium with a tone-then-silence input: speaking immediately must still arm the stop.
    let r = feed(INITIAL_VAD, 0, 0.3, 1500, 2000);
    const silenceStart = r.at;
    r = feed(r.state, r.at, 0.001, 4000, 2000);
    expect(r.state.speechHeard).toBe(true);
    expect(r.stoppedAt! - silenceStart).toBe(2000);
  });

  it('a steady noisy room alone never arms, however long', () => {
    const r = feed(INITIAL_VAD, 0, 0.05, 20_000, 2000);
    expect(r.state.speechHeard).toBe(false);
    expect(r.stoppedAt).toBeNull();
  });

  it('the floor follows a room that gets louder, and that noise is never mistaken for silence', () => {
    let r = feed(INITIAL_VAD, 0, 0.004, 2000, 2000);
    r = feed(r.state, r.at, 0.04, 4000, 2000); // a fan turns on: louder than 3x the old floor
    expect(r.state.noiseFloor!).toBeGreaterThan(0.03);
    r = feed(r.state, r.at, 0.04, 10_000, 2000);
    expect(r.stoppedAt).toBeNull();
    const quiet = r.at;
    r = feed(r.state, r.at, 0.004, 4000, 2000); // fan off
    expect(r.stoppedAt! - quiet).toBe(2000);
  });

  it('speech threshold never goes below the absolute minimum', () => {
    expect(speechThreshold(null)).toBe(MIN_SPEECH_RMS);
    expect(speechThreshold(0)).toBe(MIN_SPEECH_RMS);
  });

  it('ignores invalid samples', () => {
    const out = stepVad(INITIAL_VAD, { rms: Number.NaN, at: 0 }, 2000);
    expect(out.state).toBe(INITIAL_VAD);
    expect(out.stop).toBe(false);
  });
});
