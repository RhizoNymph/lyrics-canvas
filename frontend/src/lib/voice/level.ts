/** Input level helpers: RMS of a time-domain frame, and RMS -> 0..1 meter on a dB scale. */

/** Root mean square of samples in [-1, 1]; 0 for an empty frame. */
export function rmsOf(samples: ArrayLike<number>): number {
  const n = samples.length;
  if (n === 0) return 0;
  let sum = 0;
  for (let i = 0; i < n; i++) sum += samples[i] * samples[i];
  return Math.sqrt(sum / n);
}

export const METER_FLOOR_DB = -60;

/** -60 dBFS (or quieter) -> 0, 0 dBFS -> 1, linear in dB between. */
export function meterLevel(rms: number): number {
  if (!Number.isFinite(rms) || rms <= 0) return 0;
  const db = 20 * Math.log10(rms);
  return Math.min(1, Math.max(0, (db - METER_FLOOR_DB) / -METER_FLOOR_DB));
}
