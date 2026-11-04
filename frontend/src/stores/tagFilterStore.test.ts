import { describe, it, expect, beforeEach } from 'vitest';
import { useTagFilterStore } from './tagFilterStore';
import { EMPTY_FILTER, filterStorageKey } from '@/lib/tagFilter';
import { format } from '@/lib/tagExpr';

const data = new Map<string, string>();
(globalThis as { localStorage?: unknown }).localStorage = {
  getItem: (k: string) => data.get(k) ?? null,
  setItem: (k: string, v: string) => void data.set(k, v),
  removeItem: (k: string) => void data.delete(k),
};

beforeEach(() => {
  data.clear();
  useTagFilterStore.setState({ canvasId: null, filter: EMPTY_FILTER });
});

describe('tagFilterStore', () => {
  it('persists edits per canvas and restores them on open', () => {
    const store = useTagFilterStore.getState();
    store.open('c1');
    useTagFilterStore.getState().dispatch({ type: 'edit', text: 'love' });
    useTagFilterStore.getState().dispatch({ type: 'edit', text: 'love &' });
    expect(JSON.parse(data.get(filterStorageKey('c1')) ?? '{}')).toEqual({ v: 1, text: 'love &', appliedText: 'love' });

    useTagFilterStore.getState().open('c2');
    expect(useTagFilterStore.getState().filter).toEqual(EMPTY_FILTER);

    useTagFilterStore.getState().open('c1');
    const f = useTagFilterStore.getState().filter;
    expect(f.text).toBe('love &');
    expect(format(f.applied)).toBe('love');
  });

  it('clear removes the stored filter', () => {
    useTagFilterStore.getState().open('c1');
    useTagFilterStore.getState().dispatch({ type: 'edit', text: 'love' });
    useTagFilterStore.getState().dispatch({ type: 'clear' });
    expect(data.has(filterStorageKey('c1'))).toBe(false);
  });
});
