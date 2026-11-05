import { describe, it, expect } from 'vitest';
import { isAddSelectionShortcut, isWordBankShortcut } from './keys';

const k = (code: string, mods: Partial<{ ctrlKey: boolean; metaKey: boolean; altKey: boolean; shiftKey: boolean; repeat: boolean }>) => ({
  key: code.slice(3).toLowerCase(), code, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...mods,
});

describe('isWordBankShortcut', () => {
  it('matches Ctrl/Cmd+Shift+W and Alt+Shift+W', () => {
    expect(isWordBankShortcut(k('KeyW', { ctrlKey: true, shiftKey: true }))).toBe(true);
    expect(isWordBankShortcut(k('KeyW', { metaKey: true, shiftKey: true }))).toBe(true);
    expect(isWordBankShortcut(k('KeyW', { altKey: true, shiftKey: true }))).toBe(true);
  });

  it('rejects other chords and repeats', () => {
    expect(isWordBankShortcut(k('KeyW', { ctrlKey: true }))).toBe(false);
    expect(isWordBankShortcut(k('KeyW', { shiftKey: true }))).toBe(false);
    expect(isWordBankShortcut(k('KeyW', { ctrlKey: true, altKey: true, shiftKey: true }))).toBe(false);
    expect(isWordBankShortcut(k('KeyQ', { ctrlKey: true, shiftKey: true }))).toBe(false);
    expect(isWordBankShortcut(k('KeyW', { ctrlKey: true, shiftKey: true, repeat: true }))).toBe(false);
  });
});

describe('isAddSelectionShortcut', () => {
  it('matches Ctrl/Cmd+Shift+B only', () => {
    expect(isAddSelectionShortcut(k('KeyB', { ctrlKey: true, shiftKey: true }))).toBe(true);
    expect(isAddSelectionShortcut(k('KeyB', { metaKey: true, shiftKey: true }))).toBe(true);
    expect(isAddSelectionShortcut(k('KeyB', { ctrlKey: true }))).toBe(false);
    expect(isAddSelectionShortcut(k('KeyB', { altKey: true, shiftKey: true }))).toBe(false);
  });
});
