/**
 * Keyboard rules for the check transcript modal (dataset contract §4, web only).
 *
 * - Ctrl/Cmd+Enter saves.
 * - Ctrl+Space toggles playback. Chosen over Alt+Space because Alt+Space opens the window menu on
 *   Windows and on most Linux desktops before the page sees it, and on macOS Option+Space types a
 *   non-breaking space. Ctrl+Space reaches the page in Chrome and Firefox on Windows and Linux; on macOS
 *   it is taken by the system only when several input sources are enabled (then use the play button).
 *   Cmd+Space (Spotlight) is never used.
 * - Alt+Left / Alt+Right seek 3 s. They are prevented so they do not navigate back/forward; on macOS
 *   this replaces Option+Arrow word jumps inside the correction field. Alt+Shift+Arrow (select by word)
 *   stays native.
 * - Escape cancels (with the dirty confirm).
 */
import type { KeyLike } from './keys';

export const PLAYBACK_TOGGLE_LABEL = 'Ctrl+Space';

export type CheckKeyAction = 'save' | 'toggle_play' | 'seek_back' | 'seek_forward' | 'cancel' | 'native' | 'none';

export function checkModalKeyAction(e: KeyLike): CheckKeyAction {
  if (e.key === 'Escape') return 'cancel';
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey) return 'save';
  if (e.code === 'Space' && e.ctrlKey && !e.metaKey && !e.altKey && !e.shiftKey) return e.repeat ? 'none' : 'toggle_play';
  if (e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
    if (e.key === 'ArrowLeft') return 'seek_back';
    if (e.key === 'ArrowRight') return 'seek_forward';
  }
  return 'native';
}
