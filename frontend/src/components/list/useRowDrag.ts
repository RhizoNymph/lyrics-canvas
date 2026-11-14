import { useCallback, useEffect, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent, RefObject } from 'react';
import { dropSlot, isNoopSlot, slotToIndex } from '@/lib/listMode/reorder';

/** A drag in progress: the row, where it started, and the gap the pointer is over. */
export interface RowDrag {
  id: string;
  fromIndex: number;
  /** 0..rows: the gap before row `slot` (rows.length = after the last row). */
  slot: number;
}

/** Pixels from the list's top/bottom edge where a drag scrolls the list. */
const EDGE = 48;
const SCROLL_STEP = 14;

/**
 * Pointer drag on a row's handle (no library): pointer capture on the handle, the drop gap from the rendered
 * rows' midpoints, auto-scroll near the list edges, Escape cancels. `onDrop(id, toIndex)` runs only when
 * the drop moves the row.
 */
export function useRowDrag(
  listRef: RefObject<HTMLElement>,
  order: ReadonlyArray<string>,
  onDrop: (id: string, toIndex: number) => void,
): {
  drag: RowDrag | null;
  startDrag: (id: string, e: ReactPointerEvent<HTMLElement>) => void;
  cancelDrag: () => void;
} {
  const [drag, setDrag] = useState<RowDrag | null>(null);
  const dragRef = useRef<RowDrag | null>(null);
  dragRef.current = drag;

  const slotAt = useCallback(
    (clientY: number): number => {
      const rows = Array.from(listRef.current?.querySelectorAll<HTMLElement>('[data-snippet-id]') ?? []);
      const byId = new Map(rows.map((el) => [el.dataset.snippetId, el]));
      const mids = order.flatMap((id) => {
        const el = byId.get(id);
        if (!el) return [];
        const r = el.getBoundingClientRect();
        return [r.top + r.height / 2];
      });
      return dropSlot(mids, clientY);
    },
    [listRef, order],
  );

  const cancelDrag = useCallback(() => setDrag(null), []);

  const startDrag = useCallback(
    (id: string, e: ReactPointerEvent<HTMLElement>) => {
      if (e.button !== 0) return;
      const fromIndex = order.indexOf(id);
      if (fromIndex < 0) return;
      e.preventDefault();
      e.stopPropagation();
      const handle = e.currentTarget;
      handle.setPointerCapture(e.pointerId);
      const pointerId = e.pointerId;
      setDrag({ id, fromIndex, slot: fromIndex });

      const onMove = (ev: PointerEvent) => {
        if (ev.pointerId !== pointerId || dragRef.current === null) return;
        const list = listRef.current;
        if (list) {
          const box = list.getBoundingClientRect();
          if (ev.clientY < box.top + EDGE) list.scrollTop -= SCROLL_STEP;
          else if (ev.clientY > box.bottom - EDGE) list.scrollTop += SCROLL_STEP;
        }
        const slot = slotAt(ev.clientY);
        if (slot !== dragRef.current.slot) setDrag({ ...dragRef.current, slot });
      };
      const finish = (ev: PointerEvent) => {
        if (ev.pointerId !== pointerId) return;
        cleanup();
        const current = dragRef.current;
        setDrag(null);
        if (ev.type !== 'pointerup' || current === null) return;
        const slot = slotAt(ev.clientY);
        if (!isNoopSlot(slot, current.fromIndex)) onDrop(current.id, slotToIndex(slot, current.fromIndex));
      };
      const onKey = (ev: KeyboardEvent) => {
        if (ev.key !== 'Escape') return;
        ev.preventDefault();
        cleanup();
        setDrag(null);
      };
      const cleanup = () => {
        handle.removeEventListener('pointermove', onMove);
        handle.removeEventListener('pointerup', finish);
        handle.removeEventListener('pointercancel', finish);
        window.removeEventListener('keydown', onKey, true);
        if (handle.hasPointerCapture(pointerId)) handle.releasePointerCapture(pointerId);
      };
      handle.addEventListener('pointermove', onMove);
      handle.addEventListener('pointerup', finish);
      handle.addEventListener('pointercancel', finish);
      window.addEventListener('keydown', onKey, true);
    },
    [order, slotAt, listRef, onDrop],
  );

  // The order changed under a drag (a snippet arrived or left): its indexes are stale, so drop it.
  useEffect(() => {
    if (dragRef.current !== null && order[dragRef.current.fromIndex] !== dragRef.current.id) setDrag(null);
  }, [order]);

  return { drag, startDrag, cancelDrag };
}
