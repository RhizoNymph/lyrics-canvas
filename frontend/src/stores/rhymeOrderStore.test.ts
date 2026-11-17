import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { currentRhymeOrder, useRhymeOrderStore } from './rhymeOrderStore';
import { RHYME_ORDER_STORAGE_KEY } from '@/lib/rhyme/order';

let data: Record<string, string>;
const setItem = vi.fn((k: string, v: string) => {
  data[k] = v;
});

beforeEach(() => {
  data = {};
  setItem.mockClear();
  vi.stubGlobal('localStorage', { getItem: (k: string) => (k in data ? data[k] : null), setItem });
  useRhymeOrderStore.setState({ order: 'rhyme' });
});

afterEach(() => vi.unstubAllGlobals());

describe('rhymeOrderStore', () => {
  it('starts in rhyme order', () => {
    expect(currentRhymeOrder()).toBe('rhyme');
  });

  it('remembers a change in browser storage', () => {
    useRhymeOrderStore.getState().setOrder('meaning');
    expect(currentRhymeOrder()).toBe('meaning');
    expect(data[RHYME_ORDER_STORAGE_KEY]).toBe('meaning');
  });

  it('does not write when the order does not change', () => {
    useRhymeOrderStore.getState().setOrder('rhyme');
    expect(setItem).not.toHaveBeenCalled();
  });

  it('keeps the order in memory when storage refuses the write', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    setItem.mockImplementationOnce(() => {
      throw new Error('QuotaExceededError');
    });
    useRhymeOrderStore.getState().setOrder('meaning');
    expect(currentRhymeOrder()).toBe('meaning');
  });

  it('works without any storage', () => {
    vi.stubGlobal('localStorage', undefined);
    useRhymeOrderStore.getState().setOrder('meaning');
    expect(currentRhymeOrder()).toBe('meaning');
  });
});
