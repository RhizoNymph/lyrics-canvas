import { useCallback, useEffect } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent, RefObject } from 'react';
import { trapFocusIndex } from '@/lib/voice/keys';

const FOCUSABLE =
  'button:not([disabled]), select:not([disabled]), input:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])';

/**
 * Keep Tab focus inside `ref` while mounted: focus the container on mount, wrap Tab / Shift+Tab at the
 * ends, and on unmount give focus back to whatever had it before (when it is still in the document and
 * focus has not moved elsewhere meanwhile).
 * Returns the Tab key handler to call from the container's onKeyDown.
 */
export function useFocusTrap(ref: RefObject<HTMLElement>): (e: ReactKeyboardEvent) => void {
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const container = ref.current;
    container?.focus();
    return () => {
      // Only restore when focus is still ours (or lost); a revealed list row may have taken it.
      const active = document.activeElement;
      const ours = active === null || active === document.body || (container !== null && container.contains(active));
      if (ours && previous && document.contains(previous)) previous.focus();
    };
  }, [ref]);

  return useCallback(
    (e: ReactKeyboardEvent) => {
      const container = ref.current;
      if (!container || e.key !== 'Tab') return;
      const items = Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE));
      const current = items.indexOf(document.activeElement as HTMLElement);
      const to = trapFocusIndex(items.length, current, e.shiftKey);
      if (to === null) return;
      e.preventDefault();
      items[to].focus();
    },
    [ref],
  );
}
