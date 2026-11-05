/**
 * Where a take goes (contract §3): the snippet open in the editor gets the lines appended; otherwise a
 * new snippet at the centre of the canvas viewport in canvas coordinates. In list view the canvas is
 * not mounted, so the last measured canvas viewport is used, or the origin when the canvas was never
 * shown this session.
 */
import { viewportCenter } from '@/lib/rhyme/geometry';
import type { Point, Viewport, ViewSize } from '@/lib/rhyme/geometry';
import type { VoiceTakeTarget } from './types';

export type CaptureTarget = { kind: 'append'; snippetId: string } | { kind: 'new'; position: Point };

export interface TargetInput {
  editingSnippetId: string | null;
  viewport: Viewport;
  /** Size of the canvas element when it was last measured, or null if it never was. */
  canvasViewSize: ViewSize | null;
}

export const ORIGIN: Point = { x: 0, y: 0 };

/**
 * Where a new snippet goes: the centre of the canvas viewport, or the origin when the canvas was never
 * measured (also used by word bank drafts).
 */
export function newSnippetPosition(viewport: Viewport, canvasViewSize: ViewSize | null): Point {
  if (canvasViewSize === null || canvasViewSize.width <= 0 || canvasViewSize.height <= 0 || viewport.zoom <= 0) return ORIGIN;
  return viewportCenter(viewport, canvasViewSize);
}

export function resolveCaptureTarget({ editingSnippetId, viewport, canvasViewSize }: TargetInput): CaptureTarget {
  if (editingSnippetId !== null) return { kind: 'append', snippetId: editingSnippetId };
  return { kind: 'new', position: newSnippetPosition(viewport, canvasViewSize) };
}

export function toWireTarget(target: CaptureTarget): VoiceTakeTarget {
  return target.kind === 'append'
    ? { kind: 'append', snippet_id: target.snippetId }
    : { kind: 'new', position_x: target.position.x, position_y: target.position.y };
}
