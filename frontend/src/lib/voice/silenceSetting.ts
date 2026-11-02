/**
 * The silence auto-stop setting (seconds of continuous silence after speech; 0 = off), per viewer in
 * browser storage. Storage can be missing or throw; reads then use the default and writes return a
 * typed failure, so the in-memory value always applies for the session.
 */
import type { KeyValueStorage } from '@/lib/listMode';

export const SILENCE_CHOICES = [0, 2, 3, 5, 8, 10] as const;
export type SilenceSeconds = (typeof SILENCE_CHOICES)[number];

export const DEFAULT_SILENCE_SECONDS: SilenceSeconds = 0;
export const SILENCE_STORAGE_KEY = 'lyrics-canvas.voice.silenceAutoStopSeconds';

export type SilenceStorageError = { kind: 'unavailable' } | { kind: 'write_failed'; cause: unknown };
export type SaveSilenceResult = { ok: true } | { ok: false; error: SilenceStorageError };

export function parseSilenceSeconds(raw: string | null): SilenceSeconds | null {
  if (raw === null || raw.trim() === '') return null;
  const n = Number(raw);
  return (SILENCE_CHOICES as readonly number[]).includes(n) ? (n as SilenceSeconds) : null;
}

export function silenceLabel(seconds: SilenceSeconds): string {
  return seconds === 0 ? 'Off' : `${seconds} s`;
}

export function loadSilenceSeconds(storage: KeyValueStorage | null): SilenceSeconds {
  if (storage === null) return DEFAULT_SILENCE_SECONDS;
  let raw: string | null;
  try {
    raw = storage.getItem(SILENCE_STORAGE_KEY);
  } catch (error) {
    console.warn('voice silence setting: read failed, using default', { key: SILENCE_STORAGE_KEY, error });
    return DEFAULT_SILENCE_SECONDS;
  }
  return parseSilenceSeconds(raw) ?? DEFAULT_SILENCE_SECONDS;
}

export function saveSilenceSeconds(storage: KeyValueStorage | null, seconds: SilenceSeconds): SaveSilenceResult {
  if (storage === null) return { ok: false, error: { kind: 'unavailable' } };
  try {
    storage.setItem(SILENCE_STORAGE_KEY, String(seconds));
    return { ok: true };
  } catch (cause) {
    console.warn('voice silence setting: write failed', { key: SILENCE_STORAGE_KEY, seconds, error: cause });
    return { ok: false, error: { kind: 'write_failed', cause } };
  }
}
