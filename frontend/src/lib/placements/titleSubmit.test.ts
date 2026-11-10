import { describe, it, expect, vi } from 'vitest';
import { PlacementError } from './errors';
import { submitCanvasTitle } from './titleSubmit';

describe('submitCanvasTitle', () => {
  it('saves the trimmed title and resolves null on success', async () => {
    const save = vi.fn(async () => ({ ok: true as const, value: undefined }));
    await expect(submitCanvasTitle('  Hooks  ', save)).resolves.toBeNull();
    expect(save).toHaveBeenCalledWith('Hooks');
  });

  it('refuses an empty or too long title without saving', async () => {
    const save = vi.fn(async () => ({ ok: true as const, value: undefined }));
    await expect(submitCanvasTitle('  ', save)).resolves.toBe('A canvas title cannot be empty');
    await expect(submitCanvasTitle('x'.repeat(256), save)).resolves.toBe('Canvas titles are at most 255 characters');
    expect(save).not.toHaveBeenCalled();
  });

  it("shows the server's invalid_canvas_title and other failures as a message", async () => {
    const bad = async () => ({ ok: false as const, error: new PlacementError('invalid_canvas_title', 'rename_canvas', 'x', 422) });
    await expect(submitCanvasTitle('ok', bad)).resolves.toBe('Canvas titles are 1 to 255 characters');
    const offline = async () => ({ ok: false as const, error: new PlacementError('network', 'rename_canvas', 'x') });
    await expect(submitCanvasTitle('ok', offline)).resolves.toBe('Could not reach the server');
  });
});
