/** A bounded undo/redo stack of immutable snapshots (the open editor's local history). */

export interface History<T> {
  readonly past: readonly T[];
  readonly future: readonly T[];
}

export const HISTORY_LIMIT = 100;

export function emptyHistory<T>(): History<T> {
  return { past: [], future: [] };
}

/** Record `current` before a change; clears the redo side. */
export function record<T>(history: History<T>, current: T, limit = HISTORY_LIMIT): History<T> {
  return { past: [...history.past.slice(-(limit - 1)), current], future: [] };
}

/** Step back: the snapshot to restore and the new history, or null when there is nothing to undo. */
export function undo<T>(history: History<T>, current: T): { value: T; history: History<T> } | null {
  if (history.past.length === 0) return null;
  const value = history.past[history.past.length - 1];
  return { value, history: { past: history.past.slice(0, -1), future: [current, ...history.future] } };
}

export function redo<T>(history: History<T>, current: T): { value: T; history: History<T> } | null {
  if (history.future.length === 0) return null;
  const value = history.future[0];
  return { value, history: { past: [...history.past, current], future: history.future.slice(1) } };
}
