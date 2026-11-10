/**
 * Read models over the wire `RecordingResponse` (dataset contract §3). The wire fields are optional and
 * nullable (older backends and rows); these functions turn them into states that cannot be half-set:
 * a recording is either checked (with its text) or not, and its reference is either timed (words plus
 * spans that were validated to tile the words) or plain lines.
 */
import type { LineSpan, RecordingResponse, TimedWord } from './types';

export type Verification = { kind: 'unchecked' } | { kind: 'checked'; text: string; at: string | null };

/** Checked iff `verified_text` is set. A text without a date (a contract violation) still counts. */
export function verificationOf(r: RecordingResponse): Verification {
  const text = r.verified_text;
  if (typeof text !== 'string') return { kind: 'unchecked' };
  return { kind: 'checked', text, at: r.verified_at ?? null };
}

/** The Whisper lines of a take: `lines` when sent, else the transcript split on newlines. */
export function whisperLines(r: RecordingResponse): string[] {
  return Array.isArray(r.lines) ? r.lines : r.transcript.split('\n');
}

/** What the correction field starts with: the saved correction, else the Whisper lines. */
export function correctionPrefill(r: RecordingResponse): string {
  const v = verificationOf(r);
  return v.kind === 'checked' ? v.text : whisperLines(r).join('\n');
}

export interface TimedLine {
  text: string;
  /** Indices into `words`, ascending; empty for a blank line. */
  words: number[];
}

export type TranscriptReference =
  | { kind: 'untimed'; lines: string[] }
  | { kind: 'timed'; words: readonly TimedWord[]; lines: TimedLine[] };

/** Spans are contiguous, non-overlapping, in order and cover every word exactly once. */
function spansTile(spans: readonly LineSpan[], wordCount: number): boolean {
  let next = 0;
  for (const span of spans) {
    if (!Array.isArray(span) || span.length !== 2) return false;
    const [start, end] = span;
    if (!Number.isInteger(start) || !Number.isInteger(end) || start !== next || end < start) return false;
    next = end;
  }
  return next === wordCount;
}

/**
 * The read-only reference shown in the check view. Timings are used only when `has_timings` is set
 * and the spans tile the words; anything else falls back to plain lines (logged), never to a wrong
 * word-to-line mapping.
 */
export function referenceOf(r: RecordingResponse): TranscriptReference {
  const lines = whisperLines(r);
  const words = r.words;
  const spans = r.line_spans;
  if (r.has_timings !== true) return { kind: 'untimed', lines };
  if (!Array.isArray(words) || !Array.isArray(spans) || !spansTile(spans, words.length)) {
    console.warn('recordings: timings ignored, spans do not tile the words', {
      recordingId: r.id,
      words: Array.isArray(words) ? words.length : null,
      spans: Array.isArray(spans) ? spans.length : null,
    });
    return { kind: 'untimed', lines };
  }
  // The server's assembled lines when it sent them for every span; else the words of each span.
  const serverLines = Array.isArray(r.lines) && r.lines.length === spans.length ? r.lines : null;
  const timed = spans.map(([start, end], i): TimedLine => {
    const indices: number[] = [];
    for (let k = start; k < end; k++) indices.push(k);
    const text = serverLines ? serverLines[i] : indices.map((k) => words[k].text.trim()).join(' ');
    return { text, words: indices };
  });
  return { kind: 'timed', words, lines: timed };
}

/** Replace the row with the same id by the server's updated recording. */
export function withVerification(rows: readonly RecordingResponse[], updated: RecordingResponse): RecordingResponse[] {
  return rows.map((r) => (r.id === updated.id ? updated : r));
}
