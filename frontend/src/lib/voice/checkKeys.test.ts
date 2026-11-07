import { describe, it, expect } from 'vitest';
import { checkModalKeyAction, PLAYBACK_TOGGLE_LABEL } from './checkKeys';
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

describe('checkModalKeyAction', () => {
  it('saves on Ctrl+Enter and Cmd+Enter', () => {
    expect(checkModalKeyAction(key({ key: 'Enter', code: 'Enter', ctrlKey: true }))).toBe('save');
    expect(checkModalKeyAction(key({ key: 'Enter', code: 'Enter', metaKey: true }))).toBe('save');
    expect(checkModalKeyAction(key({ key: 'Enter', code: 'NumpadEnter', ctrlKey: true }))).toBe('save');
  });

  it('leaves plain and Shift+Enter to the textarea', () => {
    expect(checkModalKeyAction(key({ key: 'Enter', code: 'Enter' }))).toBe('native');
    expect(checkModalKeyAction(key({ key: 'Enter', code: 'Enter', shiftKey: true }))).toBe('native');
  });

  it('toggles playback on Ctrl+Space only', () => {
    expect(PLAYBACK_TOGGLE_LABEL).toBe('Ctrl+Space');
    expect(checkModalKeyAction(key({ key: ' ', code: 'Space', ctrlKey: true }))).toBe('toggle_play');
    expect(checkModalKeyAction(key({ key: ' ', code: 'Space' }))).toBe('native');
    expect(checkModalKeyAction(key({ key: ' ', code: 'Space', ctrlKey: true, shiftKey: true }))).toBe('native');
    expect(checkModalKeyAction(key({ key: ' ', code: 'Space', metaKey: true }))).toBe('native');
    expect(checkModalKeyAction(key({ key: ' ', code: 'Space', ctrlKey: true, repeat: true }))).toBe('none');
  });

  it('seeks 3 s with Alt+Left / Alt+Right, keeping Alt+Shift selection native', () => {
    expect(checkModalKeyAction(key({ key: 'ArrowLeft', code: 'ArrowLeft', altKey: true }))).toBe('seek_back');
    expect(checkModalKeyAction(key({ key: 'ArrowRight', code: 'ArrowRight', altKey: true }))).toBe('seek_forward');
    expect(checkModalKeyAction(key({ key: 'ArrowLeft', code: 'ArrowLeft' }))).toBe('native');
    expect(checkModalKeyAction(key({ key: 'ArrowLeft', code: 'ArrowLeft', altKey: true, shiftKey: true }))).toBe('native');
  });

  it('cancels on Escape', () => {
    expect(checkModalKeyAction(key({ key: 'Escape', code: 'Escape' }))).toBe('cancel');
  });
});
