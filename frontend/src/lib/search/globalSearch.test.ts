import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { GLOBAL_SEARCH_DEBOUNCE_MS, createGlobalSearch } from './globalSearch';
import type { GlobalSearchView } from './globalSearch';
import { SearchError } from './errors';
import type { GlobalSearchResponse } from '@/types/search';

const result = (title: string): GlobalSearchResponse => ({ canvases: [{ id: title, title, updated_at: '' }], snippets: [] });

function setup(search: (q: string, signal: AbortSignal) => Promise<GlobalSearchResponse>) {
  const views: GlobalSearchView[] = [];
  const controller = createGlobalSearch({ search, onView: (v) => views.push(v) });
  return { controller, views, last: () => views[views.length - 1] };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('createGlobalSearch', () => {
  it('is off for blank input and sends nothing', () => {
    const search = vi.fn();
    const { controller, last } = setup(search);
    controller.setInput('   ');
    vi.advanceTimersByTime(GLOBAL_SEARCH_DEBOUNCE_MS * 2);
    expect(last()).toEqual({ kind: 'off' });
    expect(search).not.toHaveBeenCalled();
  });

  it('debounces, sends the trimmed query once and shows the results', async () => {
    const search = vi.fn(async (q: string) => result(q));
    const { controller, last } = setup(search);
    controller.setInput('n');
    controller.setInput(' night ');
    expect(last()).toMatchObject({ kind: 'loading', q: 'night', previous: null });
    vi.advanceTimersByTime(GLOBAL_SEARCH_DEBOUNCE_MS - 1);
    expect(search).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(search).toHaveBeenCalledTimes(1);
    expect(search.mock.calls[0][0]).toBe('night');
    await vi.runAllTimersAsync();
    expect(last()).toMatchObject({ kind: 'results', q: 'night', result: result('night') });
    const view = last();
    expect(view.kind === 'results' ? view.query.terms : null).toEqual(['night']);
  });

  it('keeps the previous results while the next search loads', async () => {
    const { controller, last } = setup(async (q) => result(q));
    controller.setInput('cat');
    await vi.runAllTimersAsync();
    controller.setInput('cats');
    expect(last()).toMatchObject({ kind: 'loading', q: 'cats', previous: result('cat') });
  });

  it('drops a slower response to an older query and aborts it', async () => {
    const resolvers: Array<(r: GlobalSearchResponse) => void> = [];
    const signals: AbortSignal[] = [];
    const { controller, last } = setup(
      (q, signal) =>
        new Promise((resolve) => {
          signals.push(signal);
          resolvers.push(resolve);
        }),
    );
    controller.setInput('old');
    vi.advanceTimersByTime(GLOBAL_SEARCH_DEBOUNCE_MS);
    controller.setInput('new');
    expect(signals[0].aborted).toBe(true);
    vi.advanceTimersByTime(GLOBAL_SEARCH_DEBOUNCE_MS);
    resolvers[1](result('new'));
    resolvers[0](result('old'));
    await vi.runAllTimersAsync();
    expect(last()).toMatchObject({ kind: 'results', q: 'new', result: result('new') });
  });

  it('shows a typed failure', async () => {
    const { controller, last } = setup(async () => {
      throw new SearchError('network', 'Network Error');
    });
    controller.setInput('x');
    await vi.runAllTimersAsync();
    expect(last()).toMatchObject({ kind: 'failed', q: 'x' });
    expect((last() as { error: SearchError }).error.kind).toBe('network');
  });

  it('refuses an over-long query locally without a request', () => {
    const search = vi.fn();
    const { controller, last } = setup(search);
    controller.setInput('x'.repeat(201));
    vi.advanceTimersByTime(GLOBAL_SEARCH_DEBOUNCE_MS);
    expect(last()).toMatchObject({ kind: 'failed' });
    expect((last() as { error: SearchError }).error.kind).toBe('invalid_search_query');
    expect(search).not.toHaveBeenCalled();
  });

  it('dispose cancels the pending search', () => {
    const search = vi.fn(async (q: string) => result(q));
    const { controller } = setup(search);
    controller.setInput('x');
    controller.dispose();
    vi.advanceTimersByTime(GLOBAL_SEARCH_DEBOUNCE_MS);
    expect(search).not.toHaveBeenCalled();
  });
});
