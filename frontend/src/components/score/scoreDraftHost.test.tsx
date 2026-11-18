import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { useScoreDraftStore } from '@/stores/scoreDraftStore';
import { saveDraftedScore } from './saveDraftedScore';
import ScoreDraftHost from './ScoreDraftHost';
import { ScoreInvalid } from '@/lib/score';
import { ScoreSaveError } from '@/lib/score/saveFlow';
import type { Score } from '@/types/score';

vi.mock('@/api/client', () => ({
  scoreApi: { draft: vi.fn(), exportScore: vi.fn() },
  voiceApi: { listRecordings: vi.fn() },
  placementApi: {},
  snippetApi: {},
  canvasApi: {},
  groupApi: {},
  tagApi: {},
}));

const score: Score = {
  v: 1,
  ppq: 48,
  pickup: 0,
  meters: [{ bar: 0, num: 4, den: 4 }],
  tempos: [{ bar: 0, bpm: 90 }],
  snap: '1/8',
  lines: [],
};

beforeEach(() => useScoreDraftStore.setState({ target: null }));

describe('scoreDraftStore', () => {
  it('opens for one snippet and closes', () => {
    useScoreDraftStore.getState().open('s1');
    expect(useScoreDraftStore.getState().target).toEqual({ snippetId: 's1' });
    useScoreDraftStore.getState().close();
    expect(useScoreDraftStore.getState().target).toBeNull();
  });
});

describe('ScoreDraftHost', () => {
  it('renders nothing while closed', () => {
    expect(renderToStaticMarkup(<ScoreDraftHost />)).toBe('');
  });

  it('renders nothing for a snippet that is not on the canvas', () => {
    useScoreDraftStore.getState().open('missing');
    expect(renderToStaticMarkup(<ScoreDraftHost />)).toBe('');
  });
});

describe('saveDraftedScore', () => {
  it('saves the score and switches the snippet to the staff view', async () => {
    const update = vi.fn(async () => undefined);
    await expect(saveDraftedScore('s1', score, update)).resolves.toEqual({ ok: true });
    expect(update).toHaveBeenCalledWith('s1', { score, score_view: true });
  });

  it('reports a score the save flow refuses', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const update = vi.fn(async () => {
      throw new ScoreSaveError(new ScoreInvalid('line_count_mismatch', 'lines differ'));
    });
    const result = await saveDraftedScore('s1', score, update);
    expect(result.ok).toBe(false);
    expect(!result.ok && result.message).toMatch(/does not fit/i);
  });

  it('reports any other failure', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const update = vi.fn(async () => {
      throw new Error('Network Error');
    });
    const result = await saveDraftedScore('s1', score, update);
    expect(result).toEqual({ ok: false, message: 'Saving the score failed: Network Error' });
  });
});
