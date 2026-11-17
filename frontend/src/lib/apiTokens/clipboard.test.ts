import { describe, it, expect, vi } from 'vitest';
import { copyToClipboard } from './clipboard';

describe('copyToClipboard', () => {
  it('writes the text and reports copied', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    await expect(copyToClipboard('lct_x', { writeText })).resolves.toBe('copied');
    expect(writeText).toHaveBeenCalledWith('lct_x');
  });

  it('reports failed when the write rejects or there is no clipboard', async () => {
    await expect(copyToClipboard('lct_x', { writeText: vi.fn().mockRejectedValue(new Error('denied')) })).resolves.toBe('failed');
    await expect(copyToClipboard('lct_x', undefined)).resolves.toBe('failed');
  });
});
