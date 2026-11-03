/** Keyboard navigation of a suggestion list: wraps around, and -1 means nothing is highlighted. */
export function moveHighlight(index: number, count: number, delta: 1 | -1): number {
  if (count <= 0) return -1;
  if (index < 0 || index >= count) return delta === 1 ? 0 : count - 1;
  return (index + delta + count) % count;
}

export type MenuKey = 'ArrowDown' | 'ArrowUp' | 'Enter' | 'Tab' | 'Escape';

export type MenuKeyEffect =
  | { kind: 'move'; index: number }
  | { kind: 'accept'; index: number }
  | { kind: 'close' }
  | { kind: 'none' };

/** What a key does while the list is open with `count` items and `index` highlighted. */
export function menuKeyEffect(key: string, index: number, count: number): MenuKeyEffect {
  switch (key) {
    case 'ArrowDown':
      return { kind: 'move', index: moveHighlight(index, count, 1) };
    case 'ArrowUp':
      return { kind: 'move', index: moveHighlight(index, count, -1) };
    case 'Enter':
    case 'Tab':
      return count > 0 ? { kind: 'accept', index: index >= 0 && index < count ? index : 0 } : { kind: 'none' };
    case 'Escape':
      return { kind: 'close' };
    default:
      return { kind: 'none' };
  }
}
