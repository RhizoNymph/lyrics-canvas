/**
 * Word bank shortcuts, matched on the physical key (`code`) like the record shortcut.
 *
 * - Toggle the panel: Ctrl/Cmd+Shift+W (contract). Chrome and Firefox reserve Ctrl/Cmd+Shift+W (close
 *   window) and never deliver it to the page in a normal tab, so Alt+Shift+W is accepted as well; it
 *   is the shortcut that works everywhere.
 * - Add the editor selection: Ctrl/Cmd+Shift+B (preventable in browsers; it would toggle the bookmarks
 *   bar).
 */
import type { KeyLike } from '@/lib/voice/keys';

export const WORD_BANK_SHORTCUT_LABEL = 'Ctrl+Shift+W or Alt+Shift+W';
export const ADD_SELECTION_SHORTCUT_LABEL = 'Ctrl+Shift+B';

export function isWordBankShortcut(e: KeyLike): boolean {
  if (e.code !== 'KeyW' || !e.shiftKey || e.repeat) return false;
  const ctrl = e.ctrlKey || e.metaKey;
  return (ctrl && !e.altKey) || (e.altKey && !ctrl);
}

export function isAddSelectionShortcut(e: KeyLike): boolean {
  return e.code === 'KeyB' && e.shiftKey && (e.ctrlKey || e.metaKey) && !e.altKey && !e.repeat;
}
