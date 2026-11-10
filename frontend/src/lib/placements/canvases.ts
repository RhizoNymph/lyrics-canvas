/** The `canvases` list embedded in a snippet: ordering, local updates, the "⧉ N" badge and "Also on". */
import type { CanvasRef, Snippet } from '@/types';

function compareRefs(x: CanvasRef, y: CanvasRef): number {
  const tx = x.title.toLowerCase();
  const ty = y.title.toLowerCase();
  if (tx !== ty) return tx < ty ? -1 : 1;
  if (x.id === y.id) return 0;
  return x.id < y.id ? -1 : 1;
}

/** The server's order: lower(title), then id. Returns a new array. */
export function sortCanvasRefs(refs: readonly CanvasRef[]): CanvasRef[] {
  return [...refs].sort(compareRefs);
}

/** `refs` with `ref` in it (added, or its title refreshed); the same array when nothing changes. */
export function withCanvasRef(refs: CanvasRef[], ref: CanvasRef): CanvasRef[] {
  const existing = refs.find((r) => r.id === ref.id);
  if (existing && existing.title === ref.title) return refs;
  return sortCanvasRefs([...refs.filter((r) => r.id !== ref.id), { id: ref.id, title: ref.title }]);
}

/** `refs` after canvas `id` was renamed; the same array when it is absent or already has that title. */
export function renameCanvasRef(refs: CanvasRef[], id: string, title: string): CanvasRef[] {
  const existing = refs.find((r) => r.id === id);
  if (!existing || existing.title === title) return refs;
  return sortCanvasRefs(refs.map((r) => (r.id === id ? { id, title } : r)));
}

export function otherCanvases(snippet: Snippet, currentCanvasId: string): CanvasRef[] {
  return snippet.canvases.filter((c) => c.id !== currentCanvasId);
}

export function isOnOtherCanvases(snippet: Snippet, currentCanvasId: string): boolean {
  return snippet.canvases.some((c) => c.id !== currentCanvasId);
}

export interface CanvasBadge {
  /** How many canvases hold the snippet (always at least 2). */
  readonly count: number;
  readonly label: string;
}

/** The link badge for a snippet on more than one canvas, else null. */
export function canvasBadge(snippet: Snippet): CanvasBadge | null {
  const count = snippet.canvases.length;
  return count > 1 ? { count, label: `⧉ ${count}` } : null;
}

/** The editor's "Also on: A, B" line, or null when the snippet is only on this canvas. */
export function alsoOnText(snippet: Snippet, currentCanvasId: string): string | null {
  const others = otherCanvases(snippet, currentCanvasId);
  return others.length === 0 ? null : `Also on: ${others.map((c) => c.title).join(', ')}`;
}
