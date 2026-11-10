/** Voice dataset stats wording and export file naming (dataset contract §3, §4). */
import type { VoiceDatasetStats } from './types';

const FILENAME_PREFIX = 'lyrics-canvas-voice-dataset-';

function baseName(name: string): string | null {
  const parts = name.split(/[/\\]/);
  const last = parts[parts.length - 1].trim();
  return last === '' || last === '.' || last === '..' ? null : last;
}

/**
 * The file name from a `Content-Disposition` header: RFC 5987 `filename*` first, then `filename`
 * (quoted or not). Any directory part is dropped. Null when there is no usable name.
 */
export function filenameFromContentDisposition(header: string | null | undefined): string | null {
  if (typeof header !== 'string') return null;
  const star = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(header);
  if (star) {
    try {
      const decoded = baseName(decodeURIComponent(star[2].trim().replace(/^"|"$/g, '')));
      if (decoded) return decoded;
    } catch (error) {
      if (!(error instanceof URIError)) throw error;
      return null;
    }
  }
  const plain = /filename\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^;]+))/i.exec(header);
  if (!plain) return null;
  const raw = plain[1] !== undefined ? plain[1].replace(/\\(.)/g, '$1') : plain[2].trim();
  return baseName(raw);
}

/** `lyrics-canvas-voice-dataset-<UTC yyyymmddThhmmssZ>.zip`, the server's pattern, for when its header is unreadable. */
export function fallbackExportFilename(now: Date): string {
  const stamp = now.toISOString().replace(/\.\d{3}Z$/, 'Z').replace(/[-:]/g, '');
  return `${FILENAME_PREFIX}${stamp}.zip`;
}

/** A total audio length: `42s`, `4m 10s`, `1h 2m`. */
export function formatAudioTotal(ms: number): string {
  const total = Number.isFinite(ms) && ms > 0 ? Math.floor(ms / 1000) : 0;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

/** "12 of 15 takes checked · 4m 10s checked audio · 9 with word timings". */
export function formatDatasetStats(stats: VoiceDatasetStats): string {
  const takes = stats.total === 1 ? 'take' : 'takes';
  return `${stats.verified} of ${stats.total} ${takes} checked · ${formatAudioTotal(stats.verified_duration_ms)} checked audio · ${stats.with_timings} with word timings`;
}
