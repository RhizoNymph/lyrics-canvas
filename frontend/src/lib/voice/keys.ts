/**
 * Keyboard rules for voice capture.
 *
 * Record shortcut: Alt+Shift+R (Option+Shift+R on macOS), matched on the physical key (`code`) because
 * Option changes `key` on macOS. Chosen to avoid browser shortcuts (Ctrl/Cmd+Shift+R reloads, Ctrl+R
 * reloads, Ctrl+Alt+R is Firefox reader view on Linux) and the app's own Ctrl/Cmd+Shift+L/V/H, Ctrl+Z,
 * Ctrl+F and Delete. Being a chord, it also works while typing in the editor.
 */
export interface KeyLike {
  key: string;
  code: string;
  altKey: boolean;
  shiftKey: boolean;
  ctrlKey: boolean;
  metaKey: boolean;
  repeat?: boolean;
}

export const RECORD_SHORTCUT_LABEL = 'Alt+Shift+R';

export function isRecordShortcut(e: KeyLike): boolean {
  return e.altKey && e.shiftKey && !e.ctrlKey && !e.metaKey && e.code === 'KeyR' && !e.repeat;
}

/** What a key does inside the recording modal. Keys on buttons/selects keep their native meaning. */
export type ModalKeyAction = 'tap' | 'cancel' | 'native';

export function modalKeyAction(e: KeyLike, targetTag: string): ModalKeyAction {
  if (e.key === 'Escape') return 'cancel';
  const control = targetTag === 'BUTTON' || targetTag === 'SELECT' || targetTag === 'INPUT' || targetTag === 'TEXTAREA';
  if ((e.key === ' ' || e.key === 'Enter') && !control && !e.repeat && !e.altKey && !e.ctrlKey && !e.metaKey) return 'tap';
  return 'native';
}

/**
 * Focus trap: given the focusable elements' count, the index of the focused one (-1 when focus is
 * outside them) and the Tab direction, the index to move focus to, or null to let the browser move it.
 */
export function trapFocusIndex(count: number, current: number, backwards: boolean): number | null {
  if (count === 0) return null;
  if (current < 0) return backwards ? count - 1 : 0;
  if (backwards && current === 0) return count - 1;
  if (!backwards && current === count - 1) return 0;
  return null;
}
