import { describe, it, expect, vi } from 'vitest';
import type { Canvas } from '@/types';
import { PlacementError } from './errors';
import { createCanvasFromInput, renameCanvasIn, replaceCanvas } from './canvasList';

function canvas(id: string, title: string): Canvas {
  return { id, owner_id: 'u', title, is_public: false, share_token: null, viewport_state: null, created_at: '', updated_at: '' };
}

describe('createCanvasFromInput', () => {
  it('creates with the trimmed title', async () => {
    const create = vi.fn(async (title: string) => canvas('n', title));
    await expect(createCanvasFromInput('  Hooks ', create)).resolves.toEqual({ ok: true, value: canvas('n', 'Hooks') });
    expect(create).toHaveBeenCalledWith('Hooks');
  });

  it('a blank title creates "Untitled Canvas", as before', async () => {
    const create = vi.fn(async (title: string) => canvas('n', title));
    await createCanvasFromInput('   ', create);
    expect(create).toHaveBeenCalledWith('Untitled Canvas');
  });

  it('refuses a title over 255 characters without a request', async () => {
    const create = vi.fn(async (title: string) => canvas('n', title));
    const result = await createCanvasFromInput('x'.repeat(256), create);
    expect(result).toMatchObject({ ok: false, error: { kind: 'invalid_canvas_title', operation: 'create_canvas', status: null } });
    expect(create).not.toHaveBeenCalled();
  });

  it('returns a typed server failure', async () => {
    const create = vi.fn(async () => {
      throw new PlacementError('invalid_canvas_title', 'create_canvas', 'bad', 422);
    });
    await expect(createCanvasFromInput('ok', create)).resolves.toMatchObject({ ok: false, error: { status: 422 } });
  });
});

describe('renameCanvasIn', () => {
  it('renames through the api and returns the server canvas', async () => {
    const rename = vi.fn(async (id: string, title: string) => canvas(id, title));
    await expect(renameCanvasIn('c', 'New', rename)).resolves.toEqual({ ok: true, value: canvas('c', 'New') });
  });

  it('maps a thrown error', async () => {
    const rename = vi.fn(async () => {
      throw new Error('boom');
    });
    await expect(renameCanvasIn('c', 'New', rename)).resolves.toMatchObject({ ok: false, error: { kind: 'http', operation: 'rename_canvas' } });
  });
});

describe('replaceCanvas', () => {
  it('replaces the canvas with the same id and keeps the order', () => {
    const list = [canvas('a', 'A'), canvas('b', 'B')];
    expect(replaceCanvas(list, canvas('b', 'Z'))).toEqual([canvas('a', 'A'), canvas('b', 'Z')]);
    expect(replaceCanvas(list, canvas('x', 'X'))).toEqual(list);
  });
});
