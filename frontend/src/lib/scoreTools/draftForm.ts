/**
 * The draft dialog's form (pure): its fields, their validation into a typed draft request, the take
 * choices of a snippet, and the summary lines of a returned draft. See docs/features/web_score_tools.md.
 */
import { formatAudioTotal } from '@/lib/voice/dataset';
import { formatRecordedAt, transcriptPreview } from '@/lib/voice/format';
import { verificationOf } from '@/lib/voice/recordingView';
import type { RecordingResponse } from '@/lib/voice/types';
import type { ScoreMeterDen, ScoreSnap } from '@/types/score';
import type { DraftMeter, ScoreDraftRequest, ScoreDraftResponse } from '@/types/scoreTools';

export type DraftSource = 'grid' | 'recording';

export const METER_DENS: readonly ScoreMeterDen[] = [1, 2, 4, 8, 16, 32];

/** Raw field values; text fields stay strings until `buildDraftRequest`. */
export interface DraftForm {
  source: DraftSource;
  /** Grid drafts: the note length every syllable gets. */
  note: ScoreSnap;
  meterNum: string;
  meterDen: ScoreMeterDen;
  /** Required for grid drafts; empty for a recording draft = let the server estimate it. */
  bpm: string;
  /** Recording drafts: the chosen take. */
  recordingId: string | null;
}

export const DEFAULT_DRAFT_FORM: DraftForm = {
  source: 'grid',
  note: '1/8',
  meterNum: '4',
  meterDen: 4,
  bpm: '90',
  recordingId: null,
};

export type DraftFormIssue = 'meter' | 'bpm' | 'recording';

export type DraftFormResult = { ok: true; request: ScoreDraftRequest } | { ok: false; issues: DraftFormIssue[] };

export const DRAFT_ISSUE_TEXT: Readonly<Record<DraftFormIssue, string>> = {
  meter: 'Meter: the top number must be 1 to 32',
  bpm: 'Tempo: 20 to 400 bpm, at most two decimals',
  recording: 'Pick a take with word timings',
};

const INTEGER = /^\d+$/;
const DECIMAL = /^\d+(\.\d{1,2})?$/;

export function parseMeter(num: string, den: ScoreMeterDen): DraftMeter | null {
  const raw = num.trim();
  if (!INTEGER.test(raw)) return null;
  const n = Number(raw);
  return n >= 1 && n <= 32 ? { num: n, den } : null;
}

/** 20..400 with at most two decimals (the score format's tempo rule). */
export function parseBpm(raw: string): number | null {
  const text = raw.trim();
  if (!DECIMAL.test(text)) return null;
  const bpm = Number(text);
  return bpm >= 20 && bpm <= 400 ? bpm : null;
}

export function buildDraftRequest(form: DraftForm): DraftFormResult {
  const issues: DraftFormIssue[] = [];
  const meter = parseMeter(form.meterNum, form.meterDen);
  if (meter === null) issues.push('meter');
  const bpmBlank = form.bpm.trim() === '';
  const bpm = bpmBlank ? null : parseBpm(form.bpm);
  const bpmOk = form.source === 'recording' ? bpmBlank || bpm !== null : bpm !== null;
  if (!bpmOk) issues.push('bpm');
  if (form.source === 'recording' && form.recordingId === null) issues.push('recording');
  if (issues.length > 0 || meter === null) return { ok: false, issues };
  if (form.source === 'grid') {
    // bpmOk for grid implies bpm !== null.
    return { ok: true, request: { source: 'grid', note: form.note, meter, bpm: bpm as number } };
  }
  return { ok: true, request: { source: 'recording', recording_id: form.recordingId as string, meter, bpm } };
}

/** One take in the picker. `usable` = stored with word timings (a draft needs them). */
export interface RecordingChoice {
  id: string;
  label: string;
  usable: boolean;
}

function firstLine(text: string): string {
  const line = text.split('\n').find((l) => l.trim() !== '') ?? '';
  return transcriptPreview(line, 40);
}

/** The snippet's takes, newest first; takes without word timings are listed but not usable. */
export function recordingChoices(recordings: readonly RecordingResponse[]): RecordingChoice[] {
  return [...recordings]
    .sort((a, b) => (a.created_at < b.created_at ? 1 : a.created_at > b.created_at ? -1 : 0))
    .map((r) => {
      const v = verificationOf(r);
      const text = firstLine(v.kind === 'checked' ? v.text : r.transcript);
      return {
        id: r.id,
        label: `${formatRecordedAt(r.created_at)} · ${formatAudioTotal(r.duration_ms)} · ${text}`,
        usable: r.has_timings === true,
      };
    });
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** What the dialog shows about a returned draft before it is used. */
export function draftSummary(draft: ScoreDraftResponse): string[] {
  const lines: string[] = [];
  if (draft.bpm_estimated !== null) lines.push(`Estimated tempo: ${draft.bpm_estimated} bpm`);
  if (draft.unplaced > 0) lines.push(`${plural(draft.unplaced, 'syllable', 'syllables')} unplaced`);
  if (draft.unmatched_words > 0) lines.push(`${plural(draft.unmatched_words, 'take word', 'take words')} not matched`);
  if (draft.unplaced === 0 && draft.unmatched_words === 0) lines.push('Every syllable placed');
  return lines;
}
