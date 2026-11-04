import { describe, it, expect, beforeEach } from 'vitest';
import { useUIStore } from './uiStore';

beforeEach(() => useUIStore.setState({ rightPanel: 'none' }));

describe('uiStore: right panel', () => {
  it('starts closed', () => {
    expect(useUIStore.getInitialState().rightPanel).toBe('none');
  });

  it('toggles the word bank', () => {
    useUIStore.getState().toggleWordBank();
    expect(useUIStore.getState().rightPanel).toBe('words');
    useUIStore.getState().toggleWordBank();
    expect(useUIStore.getState().rightPanel).toBe('none');
  });

  it('keeps the rhyme sidebar and the word bank mutually exclusive', () => {
    useUIStore.getState().toggleRhymeSidebar();
    expect(useUIStore.getState().rightPanel).toBe('rhymes');
    useUIStore.getState().toggleWordBank();
    expect(useUIStore.getState().rightPanel).toBe('words');
    useUIStore.getState().toggleRhymeSidebar();
    expect(useUIStore.getState().rightPanel).toBe('rhymes');
  });
});
