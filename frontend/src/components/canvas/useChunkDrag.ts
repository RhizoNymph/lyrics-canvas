import { useCallback, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent, RefObject } from 'react';
import { gapFromX, moveTargetFromGap } from '@/lib/grid';

/** Pointer travel before a press on a selected cell becomes a drag. */
export const DRAG_THRESHOLD_PX = 4;

export interface ChunkSelection {
  start: number;
  end: number; // exclusive
}

interface Options {
  cellWidth: number;
  cellCount: number;
  selection: ChunkSelection | null;
  /** The cells strip; its left edge is the left edge of cell 0. */
  stripRef: RefObject<HTMLDivElement | null>;
  onDrop: (start: number, end: number, to: number) => void;
}

export interface ChunkDrag {
  dragging: boolean;
  /** Gap under the pointer while dragging (0..cellCount), else null. */
  dropGap: number | null;
  onPointerDown: (e: ReactPointerEvent<HTMLElement>, col: number) => void;
  onPointerMove: (e: ReactPointerEvent<HTMLElement>) => void;
  onPointerUp: (e: ReactPointerEvent<HTMLElement>) => void;
  onPointerCancel: (e: ReactPointerEvent<HTMLElement>) => void;
  /** True once if a drag just ended, so the click that follows it is ignored. */
  consumeDrag: () => boolean;
}

interface Press {
  x: number;
  y: number;
  pointerId: number;
  target: HTMLElement;
}

/** Drag a selected chunk of cells along its row; the drop is reported in `moveChunk` terms. */
export function useChunkDrag(options: Options): ChunkDrag {
  const optionsRef = useRef(options);
  optionsRef.current = options;

  const pressRef = useRef<Press | null>(null);
  const draggingRef = useRef(false);
  const gapRef = useRef<number | null>(null);
  const justDraggedRef = useRef(false);
  const [dragging, setDragging] = useState(false);
  const [dropGap, setDropGap] = useState<number | null>(null);

  const onPointerDown = useCallback((e: ReactPointerEvent<HTMLElement>, col: number) => {
    const { selection } = optionsRef.current;
    if (e.button !== 0 || !selection || col < selection.start || col >= selection.end) return;
    pressRef.current = { x: e.clientX, y: e.clientY, pointerId: e.pointerId, target: e.currentTarget };
    e.currentTarget.setPointerCapture(e.pointerId);
  }, []);

  const onPointerMove = useCallback((e: ReactPointerEvent<HTMLElement>) => {
    const press = pressRef.current;
    if (!press) return;
    if (!draggingRef.current) {
      if (Math.hypot(e.clientX - press.x, e.clientY - press.y) < DRAG_THRESHOLD_PX) return;
      draggingRef.current = true;
      setDragging(true);
    }
    const { stripRef, cellWidth, cellCount } = optionsRef.current;
    const rect = stripRef.current?.getBoundingClientRect();
    if (!rect) return;
    const gap = gapFromX(e.clientX - rect.left, cellWidth, cellCount);
    gapRef.current = gap;
    setDropGap(gap);
  }, []);

  const finish = useCallback((commit: boolean) => {
    const press = pressRef.current;
    if (!press) return;
    pressRef.current = null;
    if (press.target.hasPointerCapture(press.pointerId)) press.target.releasePointerCapture(press.pointerId);
    if (!draggingRef.current) return;
    draggingRef.current = false;
    justDraggedRef.current = true;
    // The click that follows a captured pointerup fires synchronously; if a browser skips it, do not eat the next one.
    setTimeout(() => {
      justDraggedRef.current = false;
    }, 0);
    setDragging(false);
    const gap = gapRef.current;
    gapRef.current = null;
    setDropGap(null);
    const { selection, onDrop } = optionsRef.current;
    if (!commit || !selection || gap === null) return;
    const to = moveTargetFromGap(gap, selection.start, selection.end);
    if (to !== null) onDrop(selection.start, selection.end, to);
  }, []);

  const onPointerUp = useCallback(() => finish(true), [finish]);
  const onPointerCancel = useCallback(() => finish(false), [finish]);

  const consumeDrag = useCallback(() => {
    const did = justDraggedRef.current;
    justDraggedRef.current = false;
    return did;
  }, []);

  return { dragging, dropGap, onPointerDown, onPointerMove, onPointerUp, onPointerCancel, consumeDrag };
}
