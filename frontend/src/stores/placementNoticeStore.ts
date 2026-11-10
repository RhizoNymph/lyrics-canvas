import { create } from 'zustand';
import type { CanvasRef } from '@/types';

/** The result line of a transfer / remove / delete-everywhere (bottom centre, auto-dismissed). */
export interface PlacementNotice {
  readonly id: number;
  readonly text: string;
  readonly tone: 'info' | 'error';
  /** The canvas the Open action navigates to, or null for no action. */
  readonly open: CanvasRef | null;
}

interface PlacementNoticeState {
  notice: PlacementNotice | null;
  show: (text: string, tone: PlacementNotice['tone'], open?: CanvasRef | null) => void;
  /** Dismiss only if `id` is still the shown notice (a timer must not hide a newer one). */
  dismiss: (id: number) => void;
}

let nextId = 1;

export const usePlacementNoticeStore = create<PlacementNoticeState>((set, get) => ({
  notice: null,
  show: (text, tone, open = null) => set({ notice: { id: nextId++, text, tone, open } }),
  dismiss: (id) => {
    if (get().notice?.id === id) set({ notice: null });
  },
}));
