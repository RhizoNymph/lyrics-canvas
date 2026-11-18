import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ScoreDraftView from './ScoreDraftView';
import type { ScoreDraftViewProps } from './ScoreDraftView';
import { initialDraftDialog, reduceDraftDialog } from '@/lib/scoreTools/draftDialog';
import type { DraftDialogState } from '@/lib/scoreTools/draftDialog';
import { ScoreApiError } from '@/types/scoreTools';
import type { RecordingResponse } from '@/lib/voice/types';
import type { Score } from '@/types/score';

const noop = () => undefined;

const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 0,
  meters: [{ bar: 0, num: 4, den: 4 }],
  tempos: [{ bar: 0, bpm: 90 }],
  snap: '1/8',
  lines: [],
};

function recording(id: string, has_timings: boolean, transcript: string): RecordingResponse {
  return {
    id,
    snippet_id: 's1',
    mime_type: 'audio/webm',
    duration_ms: 3000,
    byte_size: 10,
    transcript,
    split_mode: 'taps',
    created_at: `2026-11-0${id === 'a' ? 2 : 1}T00:00:00Z`,
    has_timings,
  };
}

function render(state: DraftDialogState, patch: Partial<ScoreDraftViewProps> = {}): string {
  return renderToStaticMarkup(
    <ScoreDraftView state={state} onEvent={noop} onSubmit={noop} onUse={noop} onClose={noop} {...patch} />,
  );
}

describe('ScoreDraftView', () => {
  it('is a labelled modal dialog with both sources', () => {
    const html = render(initialDraftDialog());
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('Make score');
    expect(html).toContain('From grid');
    expect(html).toContain('From recording');
  });

  it('grid source: note length, meter and a required tempo', () => {
    const html = render(initialDraftDialog());
    expect(html).toContain('data-field="note"');
    expect(html).toContain('data-field="meter-num"');
    expect(html).toContain('data-field="meter-den"');
    expect(html).toContain('data-field="bpm"');
    expect(html).not.toContain('data-field="recording"');
    for (const snap of ['1/8', '1/16', '1/32', '1/8t', '1/16t']) expect(html).toContain(`value="${snap}"`);
  });

  it('recording source: a take picker, meter and an optional tempo, no note length', () => {
    const state = reduceDraftDialog(initialDraftDialog('recording'), {
      type: 'recordings_loaded',
      recordings: [recording('a', true, 'orange door'), recording('b', false, 'old take')],
    });
    const html = render(state);
    expect(html).toContain('data-field="recording"');
    expect(html).not.toContain('data-field="note"');
    expect(html).toContain('orange door');
    expect(html).toMatch(/<option[^>]*disabled=""[^>]*>[^<]*old take[^<]*no word timings/);
    expect(html).toMatch(/placeholder="estimate"/);
  });

  it('recording source without takes says so', () => {
    const state = reduceDraftDialog(initialDraftDialog('recording'), { type: 'recordings_loaded', recordings: [] });
    expect(render(state)).toMatch(/no recordings/i);
  });

  it('recording source with a failed take list shows the failure', () => {
    const state = reduceDraftDialog(initialDraftDialog('recording'), { type: 'recordings_failed', message: 'Could not reach the server' });
    expect(render(state)).toContain('Could not reach the server');
  });

  it('lists the form issues', () => {
    const state = reduceDraftDialog(reduceDraftDialog(initialDraftDialog(), { type: 'edit', patch: { bpm: 'x' } }), { type: 'submit' });
    const html = render(state);
    expect(html).toContain('role="alert"');
    expect(html).toMatch(/Tempo: 20 to 400 bpm/);
  });

  it('disables Draft while drafting', () => {
    const state = reduceDraftDialog(initialDraftDialog(), { type: 'submit' });
    expect(render(state)).toMatch(/<button[^>]*disabled=""[^>]*>Drafting…<\/button>/);
  });

  it('shows the draft summary and the Use score action', () => {
    let state = reduceDraftDialog(initialDraftDialog('recording'), { type: 'edit', patch: { recordingId: 'a' } });
    state = reduceDraftDialog(state, { type: 'submit' });
    state = reduceDraftDialog(state, { type: 'drafted', draft: { score, bpm_estimated: 93.5, unplaced: 2, unmatched_words: 1 } });
    const html = render(state);
    expect(html).toContain('data-draft-summary');
    expect(html).toContain('Estimated tempo: 93.5 bpm');
    expect(html).toContain('2 syllables unplaced');
    expect(html).toContain('1 take word not matched');
    expect(html).toContain('Use score');
  });

  it('shows a failure with its hint', () => {
    let state = reduceDraftDialog(initialDraftDialog(), { type: 'submit' });
    state = reduceDraftDialog(state, { type: 'failed', error: new ScoreApiError('recording_has_no_timings', 'draft', 'x', 422) });
    const html = render(state);
    expect(html).toContain('role="alert"');
    expect(html).toContain('This take has no word timings');
    expect(html).toMatch(/record a new take/);
  });
});
