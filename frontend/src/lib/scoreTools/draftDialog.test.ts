import { describe, it, expect } from 'vitest';
import { initialDraftDialog, reduceDraftDialog } from './draftDialog';
import type { DraftDialogState } from './draftDialog';
import { DEFAULT_DRAFT_FORM } from './draftForm';
import { ScoreApiError } from '@/types/scoreTools';
import type { ScoreDraftResponse } from '@/types/scoreTools';
import type { RecordingResponse } from '@/lib/voice/types';
import type { Score } from '@/types/score';

const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 0,
  meters: [{ bar: 0, num: 4, den: 4 }],
  tempos: [{ bar: 0, bpm: 90 }],
  snap: '1/8',
  lines: [],
};
const draft: ScoreDraftResponse = { score, bpm_estimated: 91, unplaced: 2, unmatched_words: 0 };

function recording(id: string, created_at: string, has_timings: boolean): RecordingResponse {
  return {
    id,
    snippet_id: 's1',
    mime_type: 'audio/webm',
    duration_ms: 3000,
    byte_size: 10,
    transcript: 'la la',
    split_mode: 'taps',
    created_at,
    has_timings,
  };
}

function run(state: DraftDialogState, ...events: Parameters<typeof reduceDraftDialog>[1][]): DraftDialogState {
  return events.reduce(reduceDraftDialog, state);
}

describe('initialDraftDialog', () => {
  it('starts editing the default form with the takes loading', () => {
    const s = initialDraftDialog();
    expect(s.form).toEqual(DEFAULT_DRAFT_FORM);
    expect(s.phase).toEqual({ kind: 'editing', issues: [] });
    expect(s.recordings).toEqual({ kind: 'loading' });
  });

  it('can start on the recording source', () => {
    expect(initialDraftDialog('recording').form.source).toBe('recording');
  });
});

describe('reduceDraftDialog', () => {
  it('picks the newest take with timings when the takes load', () => {
    const s = run(initialDraftDialog(), {
      type: 'recordings_loaded',
      recordings: [
        recording('old', '2026-11-01T00:00:00Z', true),
        recording('newest', '2026-11-09T00:00:00Z', false),
        recording('mid', '2026-11-05T00:00:00Z', true),
      ],
    });
    expect(s.form.recordingId).toBe('mid');
    expect(s.recordings.kind === 'loaded' && s.recordings.choices.map((c) => c.id)).toEqual(['newest', 'mid', 'old']);
  });

  it('keeps no pick when no take has timings', () => {
    const s = run(initialDraftDialog(), { type: 'recordings_loaded', recordings: [recording('x', '2026-11-01T00:00:00Z', false)] });
    expect(s.form.recordingId).toBeNull();
  });

  it('keeps a pick already made', () => {
    const s = run(
      initialDraftDialog(),
      { type: 'edit', patch: { recordingId: 'chosen' } },
      { type: 'recordings_loaded', recordings: [recording('a', '2026-11-01T00:00:00Z', true)] },
    );
    expect(s.form.recordingId).toBe('chosen');
  });

  it('records a failed take list', () => {
    const s = run(initialDraftDialog(), { type: 'recordings_failed', message: 'Could not reach the server' });
    expect(s.recordings).toEqual({ kind: 'failed', message: 'Could not reach the server' });
  });

  it('submit with a bad form lists the issues and does not draft', () => {
    const s = run(initialDraftDialog(), { type: 'edit', patch: { bpm: '1000' } }, { type: 'submit' });
    expect(s.phase).toEqual({ kind: 'editing', issues: ['bpm'] });
  });

  it('submit with a good form starts drafting with the request', () => {
    const s = run(initialDraftDialog(), { type: 'submit' });
    expect(s.phase).toEqual({
      kind: 'drafting',
      request: { source: 'grid', note: '1/8', meter: { num: 4, den: 4 }, bpm: 90 },
    });
  });

  it('a submit while drafting is ignored', () => {
    const drafting = run(initialDraftDialog(), { type: 'submit' });
    expect(reduceDraftDialog(drafting, { type: 'submit' })).toBe(drafting);
  });

  it('shows the draft, or the error', () => {
    const drafting = run(initialDraftDialog(), { type: 'submit' });
    expect(reduceDraftDialog(drafting, { type: 'drafted', draft }).phase).toEqual({ kind: 'drafted', draft });
    const error = new ScoreApiError('draft_empty', 'draft', 'empty', 422);
    expect(reduceDraftDialog(drafting, { type: 'failed', error }).phase).toEqual({ kind: 'failed', error });
  });

  it('ignores a late answer after an edit', () => {
    const edited = run(initialDraftDialog(), { type: 'submit' }, { type: 'edit', patch: { bpm: '100' } });
    expect(edited.phase).toEqual({ kind: 'editing', issues: [] });
    expect(reduceDraftDialog(edited, { type: 'drafted', draft })).toBe(edited);
    const error = new ScoreApiError('network', 'draft', 'x');
    expect(reduceDraftDialog(edited, { type: 'failed', error })).toBe(edited);
  });

  it('an edit drops a shown draft and clears the issues', () => {
    const s = run(
      initialDraftDialog(),
      { type: 'submit' },
      { type: 'drafted', draft },
      { type: 'edit', patch: { source: 'recording' } },
    );
    expect(s.phase).toEqual({ kind: 'editing', issues: [] });
    expect(s.form.source).toBe('recording');
  });
});
