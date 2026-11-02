/** Display formatting for the recorder and the recordings list. */

/** `m:ss` (or `h:mm:ss`); negative and non-finite values read as 0. */
export function formatDuration(ms: number): string {
  const total = Number.isFinite(ms) && ms > 0 ? Math.floor(ms / 1000) : 0;
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

/** One-line preview of a transcript: lines joined with " / ", cut at `max` characters with an ellipsis. */
export function transcriptPreview(transcript: string, max = 60): string {
  const flat = transcript
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l !== '')
    .join(' / ');
  if (flat.length <= max) return flat;
  return `${flat.slice(0, Math.max(0, max - 1)).trimEnd()}…`;
}

/** A short local date-time for a server timestamp (ISO-8601 or `str(datetime)`), or the raw text. */
export function formatRecordedAt(timestamp: string, locale?: string): string {
  const ms = Date.parse(timestamp);
  if (Number.isNaN(ms)) return timestamp;
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(ms));
}
