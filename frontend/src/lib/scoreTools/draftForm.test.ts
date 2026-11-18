import { describe, it, expect } from 'vitest';
import {
  DEFAULT_DRAFT_FORM,
  buildDraftRequest,
  draftSummary,
  parseBpm,
  parseMeter,
  recordingChoices,
} from './draftForm';
import type { DraftForm } from './draftForm';
import type { RecordingResponse } from '@/lib/voice/types';
import type { Score } from '@/types/score';

function form(patch: Partial<DraftForm>): DraftForm {
  return { ...DEFAULT_DRAFT_FORM, ...patch };
}

function recording(id: string, patch: Partial<RecordingResponse> = {}): RecordingResponse {
  return {
    id,
    snippet_id: 's1',
    mime_type: 'audio/webm',
    duration_ms: 4200,
    byte_size: 100,
    transcript: 'orange door\nhinge',
    split_mode: 'taps',
    created_at: '2026-11-10T12:00:00Z',
    has_timings: true,
    ...patch,
  };
}

const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 0,
  meters: [{ bar: 0, num: 4, den: 4 }],
  tempos: [{ bar: 0, bpm: 90 }],
  snap: '1/16',
  lines: [{ notes: [] }],
};

describe('DEFAULT_DRAFT_FORM', () => {
  it('starts from the grid in 4/4 at 90 bpm with eighths', () => {
    expect(DEFAULT_DRAFT_FORM).toEqual({
      source: 'grid',
      note: '1/8',
      meterNum: '4',
      meterDen: 4,
      bpm: '90',
      recordingId: null,
    });
  });
});

describe('parseMeter', () => {
  it('accepts 1..32 over an allowed denominator', () => {
    expect(parseMeter('4', 4)).toEqual({ num: 4, den: 4 });
    expect(parseMeter(' 7 ', 8)).toEqual({ num: 7, den: 8 });
    expect(parseMeter('32', 32)).toEqual({ num: 32, den: 32 });
    expect(parseMeter('1', 1)).toEqual({ num: 1, den: 1 });
  });

  it('rejects other numerators', () => {
    for (const raw of ['0', '33', '', '3.5', 'x', '-1', '1e1']) expect(parseMeter(raw, 4)).toBeNull();
  });
});

describe('parseBpm', () => {
  it('accepts 20..400 with at most two decimals', () => {
    expect(parseBpm('90')).toBe(90);
    expect(parseBpm('20')).toBe(20);
    expect(parseBpm('400')).toBe(400);
    expect(parseBpm(' 92.25 ')).toBe(92.25);
  });

  it('rejects the rest', () => {
    for (const raw of ['19.99', '400.01', '92.125', 'fast', '', '1e2x', 'Infinity']) expect(parseBpm(raw)).toBeNull();
  });
});

describe('buildDraftRequest', () => {
  it('builds a grid draft', () => {
    expect(buildDraftRequest(form({ note: '1/16', meterNum: '3', meterDen: 4, bpm: '120' }))).toEqual({
      ok: true,
      request: { source: 'grid', note: '1/16', meter: { num: 3, den: 4 }, bpm: 120 },
    });
  });

  it('requires a bpm for a grid draft', () => {
    expect(buildDraftRequest(form({ bpm: '' }))).toEqual({ ok: false, issues: ['bpm'] });
  });

  it('builds a recording draft with an estimated or a given tempo', () => {
    expect(buildDraftRequest(form({ source: 'recording', recordingId: 'r1', bpm: '' }))).toEqual({
      ok: true,
      request: { source: 'recording', recording_id: 'r1', meter: { num: 4, den: 4 }, bpm: null },
    });
    expect(buildDraftRequest(form({ source: 'recording', recordingId: 'r1', bpm: '100.5' }))).toEqual({
      ok: true,
      request: { source: 'recording', recording_id: 'r1', meter: { num: 4, den: 4 }, bpm: 100.5 },
    });
  });

  it('reports every issue at once', () => {
    expect(buildDraftRequest(form({ source: 'recording', recordingId: null, meterNum: '40', bpm: '5' }))).toEqual({
      ok: false,
      issues: ['meter', 'bpm', 'recording'],
    });
  });

  it('ignores the recording choice for a grid draft', () => {
    const built = buildDraftRequest(form({ recordingId: 'r1' }));
    expect(built.ok && built.request.source).toBe('grid');
  });
});

describe('recordingChoices', () => {
  it('lists newest first and marks takes without timings unusable', () => {
    const choices = recordingChoices([
      recording('old', { created_at: '2026-11-01T10:00:00Z' }),
      recording('none', { created_at: '2026-11-05T10:00:00Z', has_timings: false }),
      recording('new', { created_at: '2026-11-09T10:00:00Z' }),
    ]);
    expect(choices.map((c) => c.id)).toEqual(['new', 'none', 'old']);
    expect(choices.map((c) => c.usable)).toEqual([true, false, true]);
  });

  it('treats a missing has_timings (older backend) as unusable', () => {
    const r = recording('x');
    delete r.has_timings;
    expect(recordingChoices([r])[0].usable).toBe(false);
  });

  it('labels a take with its length and first line', () => {
    const [c] = recordingChoices([recording('r', { duration_ms: 65_000, transcript: 'orange door\nhinge' })]);
    expect(c.label).toContain('1m 5s');
    expect(c.label).toContain('orange door');
    expect(c.label).not.toContain('hinge');
  });

  it('prefers the checked transcript in the label', () => {
    const [c] = recordingChoices([
      recording('r', { verified_text: 'orange door hinge', verified_at: '2026-11-10T12:01:00Z' }),
    ]);
    expect(c.label).toContain('orange door hinge');
  });
});

describe('draftSummary', () => {
  it('shows the estimated tempo and both counts', () => {
    expect(draftSummary({ score, bpm_estimated: 92.5, unplaced: 3, unmatched_words: 1 })).toEqual([
      'Estimated tempo: 92.5 bpm',
      '3 syllables unplaced',
      '1 take word not matched',
    ]);
  });

  it('says everything was placed when the counts are zero', () => {
    expect(draftSummary({ score, bpm_estimated: null, unplaced: 0, unmatched_words: 0 })).toEqual(['Every syllable placed']);
  });

  it('uses singular and plural forms', () => {
    expect(draftSummary({ score, bpm_estimated: null, unplaced: 1, unmatched_words: 2 })).toEqual([
      '1 syllable unplaced',
      '2 take words not matched',
    ]);
  });
});
