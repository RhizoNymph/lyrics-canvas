import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createDebouncedRunner } from './debounce';

describe('createDebouncedRunner', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('coalesces calls inside the window into one', () => {
    const fn = vi.fn();
    const r = createDebouncedRunner(fn, 150);
    r.schedule();
    vi.advanceTimersByTime(100);
    r.schedule();
    vi.advanceTimersByTime(100);
    expect(fn).not.toHaveBeenCalled();
    vi.advanceTimersByTime(50);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('runNow fires immediately and cancels a pending run', () => {
    const fn = vi.fn();
    const r = createDebouncedRunner(fn, 150);
    r.schedule();
    r.runNow();
    expect(fn).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(200);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('cancel drops a pending run', () => {
    const fn = vi.fn();
    const r = createDebouncedRunner(fn, 150);
    r.schedule();
    r.cancel();
    vi.advanceTimersByTime(200);
    expect(fn).not.toHaveBeenCalled();
  });
});
