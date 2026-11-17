import { describe, it, expect, vi } from 'vitest';
import { runDraft } from './drafter';
import type { LocalGridDrafter } from './drafter';
import { ScoreApiError } from '@/types/scoreTools';
import type { GridDraftRequest, RecordingDraftRequest, ScoreDraftResponse } from '@/types/scoreTools';
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
const gridRequest: GridDraftRequest = { source: 'grid', note: '1/8', meter: { num: 4, den: 4 }, bpm: 90 };
const recordingRequest: RecordingDraftRequest = { source: 'recording', recording_id: 'r1', meter: { num: 4, den: 4 }, bpm: null };
const serverDraft: ScoreDraftResponse = { score, bpm_estimated: 88, unplaced: 1, unmatched_words: 2 };

describe('runDraft', () => {
  it('asks the server when no local grid drafter is given', async () => {
    const draft = vi.fn(async () => serverDraft);
    await expect(runDraft('s1', gridRequest, { draft })).resolves.toBe(serverDraft);
    expect(draft).toHaveBeenCalledWith('s1', gridRequest, undefined);
  });

  it('drafts a grid locally when a drafter is given, placing every syllable', async () => {
    const draft = vi.fn(async () => serverDraft);
    const local: LocalGridDrafter = vi.fn(() => ({ ok: true as const, score }));
    await expect(runDraft('s1', gridRequest, { draft, local })).resolves.toEqual({
      score,
      bpm_estimated: null,
      unplaced: 0,
      unmatched_words: 0,
    });
    expect(local).toHaveBeenCalledWith(gridRequest);
    expect(draft).not.toHaveBeenCalled();
  });

  it('rejects with the local drafter error', async () => {
    const error = new ScoreApiError('invalid_score', 'draft', 'too long', null, { reason: 'exceeds_max_ticks', line: null, note: null });
    const local: LocalGridDrafter = () => ({ ok: false, error });
    await expect(runDraft('s1', gridRequest, { draft: vi.fn(), local })).rejects.toBe(error);
  });

  it('always asks the server for a recording draft', async () => {
    const draft = vi.fn(async () => serverDraft);
    const local: LocalGridDrafter = vi.fn(() => ({ ok: true as const, score }));
    const abort = new AbortController();
    await expect(runDraft('s1', recordingRequest, { draft, local }, abort.signal)).resolves.toBe(serverDraft);
    expect(draft).toHaveBeenCalledWith('s1', recordingRequest, abort.signal);
    expect(local).not.toHaveBeenCalled();
  });
});
