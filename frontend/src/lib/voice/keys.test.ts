import { describe, it, expect } from 'vitest';
import { isRecordShortcut, modalKeyAction, trapFocusIndex } from './keys';
import type { KeyLike } from './keys';

const key = (over: Partial<KeyLike>): KeyLike => ({
  key: '',
  code: '',
  altKey: false,
  shiftKey: false,
  ctrlKey: false,
  metaKey: false,
  ...over,
});

describe('isRecordShortcut', () => {
  it('matches Alt+Shift+R by physical key (macOS Option changes key)', () => {
    expect(isRecordShortcut(key({ key: 'R', code: 'KeyR', altKey: true, shiftKey: true }))).toBe(true);
    expect(isRecordShortcut(key({ key: '‰', code: 'KeyR', altKey: true, shiftKey: true }))).toBe(true);
  });

  it('does not clash with reload or other chords', () => {
    expect(isRecordShortcut(key({ key: 'R', code: 'KeyR', ctrlKey: true, shiftKey: true }))).toBe(false);
    expect(isRecordShortcut(key({ key: 'r', code: 'KeyR', ctrlKey: true }))).toBe(false);
    expect(isRecordShortcut(key({ key: 'r', code: 'KeyR', ctrlKey: true, altKey: true }))).toBe(false);
    expect(isRecordShortcut(key({ key: 'R', code: 'KeyR', altKey: true, shiftKey: true, metaKey: true }))).toBe(false);
    expect(isRecordShortcut(key({ key: 'r', code: 'KeyR' }))).toBe(false);
    expect(isRecordShortcut(key({ key: 'R', code: 'KeyR', altKey: true, shiftKey: true, repeat: true }))).toBe(false);
  });
});

describe('modalKeyAction', () => {
  it('Space and Enter on the surface are taps; Escape cancels anywhere', () => {
    expect(modalKeyAction(key({ key: ' ' }), 'DIV')).toBe('tap');
    expect(modalKeyAction(key({ key: 'Enter' }), 'DIV')).toBe('tap');
    expect(modalKeyAction(key({ key: 'Escape' }), 'BUTTON')).toBe('cancel');
  });

  it('keeps native behaviour on buttons and selects, and ignores auto-repeat', () => {
    expect(modalKeyAction(key({ key: ' ' }), 'BUTTON')).toBe('native');
    expect(modalKeyAction(key({ key: 'Enter' }), 'SELECT')).toBe('native');
    expect(modalKeyAction(key({ key: ' ', repeat: true }), 'DIV')).toBe('native');
    expect(modalKeyAction(key({ key: 'a' }), 'DIV')).toBe('native');
  });
});

describe('trapFocusIndex', () => {
  it('wraps at both ends and pulls focus back in from outside', () => {
    expect(trapFocusIndex(3, 2, false)).toBe(0);
    expect(trapFocusIndex(3, 0, true)).toBe(2);
    expect(trapFocusIndex(3, 1, false)).toBeNull();
    expect(trapFocusIndex(3, -1, false)).toBe(0);
    expect(trapFocusIndex(3, -1, true)).toBe(2);
    expect(trapFocusIndex(0, -1, false)).toBeNull();
  });
});
