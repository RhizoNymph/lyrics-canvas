import { describe, it, expect, vi } from 'vitest';
import { exportScoreFile } from './exportScore';
import { ScoreApiError } from '@/types/scoreTools';

vi.mock('@/api/client', () => ({ scoreApi: { exportScore: vi.fn() } }));

describe('exportScoreFile', () => {
  it('downloads and saves the file under the server name', async () => {
    const blob = new Blob(['x']);
    const exportScore = vi.fn(async () => ({ blob, filename: 'Night drive.mid' }));
    const save = vi.fn();
    const outcome = await exportScoreFile('s1', 'midi', { exportScore, save });
    expect(outcome).toEqual({ ok: true, filename: 'Night drive.mid' });
    expect(exportScore).toHaveBeenCalledWith('s1', 'midi');
    expect(save).toHaveBeenCalledWith(blob, 'Night drive.mid');
  });

  it('returns the typed failure and saves nothing', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const error = new ScoreApiError('score_not_found', 'export', 'none', 404);
    const save = vi.fn();
    const outcome = await exportScoreFile('s1', 'musicxml', {
      exportScore: async () => {
        throw error;
      },
      save,
    });
    expect(outcome).toEqual({ ok: false, error });
    expect(save).not.toHaveBeenCalled();
  });

  it('wraps an unexpected rejection into a ScoreApiError', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const outcome = await exportScoreFile('s1', 'midi', {
      exportScore: async () => {
        throw new Error('boom');
      },
      save: vi.fn(),
    });
    expect(outcome.ok).toBe(false);
    expect(!outcome.ok && outcome.error).toBeInstanceOf(ScoreApiError);
  });
});
