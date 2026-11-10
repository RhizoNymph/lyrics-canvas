/** Move to canvas / Also show on canvas: picker list, request checks, local result, result notice. */
import type { Canvas, CanvasRef, Snippet, TransferMode, TransferResponse } from '@/types';
import { withCanvasRef } from './canvases';

/** The server accepts 1..500 snippet ids per transfer. */
export const TRANSFER_MAX = 500;

/** The picker's canvases: every canvas except the current one, title containing `query`, newest updated first. */
export function pickerCanvases(canvases: readonly Canvas[], currentCanvasId: string, query: string): Canvas[] {
  const q = query.trim().toLowerCase();
  return canvases
    .filter((c) => c.id !== currentCanvasId && (q === '' || c.title.toLowerCase().includes(q)))
    .sort((x, y) => Date.parse(y.updated_at) - Date.parse(x.updated_at));
}

export type TransferIdsCheck =
  | { ok: true; ids: string[] }
  | { ok: false; kind: 'nothing_selected' | 'too_many_snippets' };

export function checkTransferIds(ids: readonly string[]): TransferIdsCheck {
  const distinct = [...new Set(ids)];
  if (distinct.length === 0) return { ok: false, kind: 'nothing_selected' };
  if (distinct.length > TRANSFER_MAX) return { ok: false, kind: 'too_many_snippets' };
  return { ok: true, ids: distinct };
}

function count(n: number): string {
  return `${n} snippet${n === 1 ? '' : 's'}`;
}

/** "Moved 3 snippets to Title", "Showing 3 snippets on Title (1 was already there)". */
export function transferNotice(mode: TransferMode, targetTitle: string, response: TransferResponse): string {
  const total = response.placed.length + response.already_on_target.length;
  const already = response.already_on_target.length;
  const suffix = already === 0 ? '' : ` (${already} ${already === 1 ? 'was' : 'were'} already there)`;
  return mode === 'move'
    ? `Moved ${count(total)} to ${targetTitle}${suffix}`
    : `Showing ${count(total)} on ${targetTitle}${suffix}`;
}

/** After "Also show on": `target` joins the `canvases` of each transferred snippet that is on this canvas. */
export function showOnTarget(
  snippets: ReadonlyMap<string, Snippet>,
  ids: readonly string[],
  target: CanvasRef,
): Map<string, Snippet> {
  const next = new Map(snippets);
  for (const id of ids) {
    const s = next.get(id);
    if (!s) continue;
    const canvases = withCanvasRef(s.canvases, target);
    if (canvases !== s.canvases) next.set(id, { ...s, canvases });
  }
  return next;
}
