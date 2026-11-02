/**
 * Silence auto-stop: an energy VAD over the mic's RMS level with an adaptive noise floor.
 *
 * - Noise floor (minimum statistics): the lowest RMS in the trailing FLOOR_WINDOW_MS. It follows a room
 *   that gets quieter at once and one that gets louder within the window, and is never seeded from a
 *   single sample, so speech that starts immediately does not become "the floor".
 * - Speech threshold: max(MIN_SPEECH_RMS, floor * SPEECH_RATIO). A sample is silence when it is below
 *   the threshold and at most 1/SPEECH_RATIO of the loudest sustained level (or of MIN_SPEECH_RMS).
 * - Arming: speech counts as heard once a level sustained for MIN_SPEECH_MS (the minimum over that
 *   span, so a click or a tap on the device does not count) has reached the current threshold. The peak
 *   sustained level is remembered, so a take that starts with speech arms as soon as the floor drops.
 * - Stop: armed, and the current run of silence has lasted `silenceMs`. `silenceMs` = 0 turns it off.
 *
 * Pure: the adapter samples an AnalyserNode and feeds `{rms, at}`; `at` is any monotonic ms clock.
 */
export const MIN_SPEECH_RMS = 0.02;
export const SPEECH_RATIO = 3;
export const MIN_SPEECH_MS = 200;
export const FLOOR_WINDOW_MS = 3000;
const MIN_FLOOR = 0.0005;

export interface VadSample {
  rms: number;
  at: number;
}

export interface VadState {
  /** Samples within the trailing FLOOR_WINDOW_MS, oldest first. */
  recent: readonly VadSample[];
  /** The current floor (minimum of `recent`), or null before the first sample. */
  noiseFloor: number | null;
  /** Highest level sustained for MIN_SPEECH_MS so far. */
  peakSustained: number;
  speechHeard: boolean;
  /** Start of the current run of silent samples, or null. */
  silentSince: number | null;
}

export const INITIAL_VAD: VadState = { recent: [], noiseFloor: null, peakSustained: 0, speechHeard: false, silentSince: null };

export interface VadStep {
  state: VadState;
  stop: boolean;
}

export function speechThreshold(noiseFloor: number | null): number {
  return Math.max(MIN_SPEECH_RMS, (noiseFloor ?? 0) * SPEECH_RATIO);
}

function minRms(samples: readonly VadSample[]): number {
  let min = Number.POSITIVE_INFINITY;
  for (const s of samples) if (s.rms < min) min = s.rms;
  return min;
}

export function stepVad(state: VadState, sample: VadSample, silenceMs: number): VadStep {
  const { rms, at } = sample;
  if (!Number.isFinite(rms) || rms < 0 || !Number.isFinite(at)) return { state, stop: false };

  const recent = [...state.recent.filter((s) => at - s.at < FLOOR_WINDOW_MS && s.at <= at), sample];
  const noiseFloor = Math.max(MIN_FLOOR, minRms(recent));
  const threshold = speechThreshold(noiseFloor);

  const span = recent.filter((s) => at - s.at <= MIN_SPEECH_MS);
  const sustained = span.length > 0 && at - span[0].at >= MIN_SPEECH_MS ? minRms(span) : 0;
  const peakSustained = Math.max(state.peakSustained, sustained);
  const speechHeard = state.speechHeard || peakSustained >= threshold;

  // Silence is below the floor-relative threshold and well below the loudest sustained level, so a
  // loud steady signal is never "silence" while the floor window still sits at its level.
  const silent = rms < threshold && rms * SPEECH_RATIO <= Math.max(peakSustained, MIN_SPEECH_RMS);
  const silentSince = silent ? (state.silentSince ?? at) : null;

  const next: VadState = { recent, noiseFloor, peakSustained, speechHeard, silentSince };
  const stop = silenceMs > 0 && speechHeard && silentSince !== null && at - silentSince >= silenceMs;
  return { state: next, stop };
}
