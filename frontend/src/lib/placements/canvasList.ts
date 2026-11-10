/** Canvas create and rename outside an open canvas (canvas list page, the picker's "New canvas…"). */
import type { Canvas } from '@/types';
import { PlacementError, toPlacementError } from './errors';
import type { PlacementResult } from './errors';
import { checkNewCanvasTitle, titleProblemMessage } from './title';

/** Create a canvas from what the user typed: blank becomes "Untitled Canvas"; over 255 characters is refused. */
export async function createCanvasFromInput(
  raw: string,
  create: (title: string) => Promise<Canvas>,
): Promise<PlacementResult<Canvas>> {
  const check = checkNewCanvasTitle(raw);
  if (!check.ok) {
    return { ok: false, error: new PlacementError('invalid_canvas_title', 'create_canvas', titleProblemMessage(check.problem)) };
  }
  try {
    return { ok: true, value: await create(check.title) };
  } catch (err) {
    return { ok: false, error: toPlacementError(err, 'create_canvas') };
  }
}

/** Rename a canvas by id (the title is already checked by the editor). */
export async function renameCanvasIn(
  id: string,
  title: string,
  rename: (id: string, title: string) => Promise<Canvas>,
): Promise<PlacementResult<Canvas>> {
  try {
    return { ok: true, value: await rename(id, title) };
  } catch (err) {
    return { ok: false, error: toPlacementError(err, 'rename_canvas') };
  }
}

export function replaceCanvas(list: readonly Canvas[], updated: Canvas): Canvas[] {
  return list.map((c) => (c.id === updated.id ? updated : c));
}
