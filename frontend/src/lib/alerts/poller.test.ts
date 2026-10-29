import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ALERTS_ABSENT_POLL_MS, ALERTS_POLL_MS, createAlertsPoller } from './poller';
import type { AlertFetchResult } from './types';

const NONE: AlertFetchResult = { kind: 'alerts', alerts: [], malformed: 0 };

function setup(results: (AlertFetchResult | null)[]) {
  const queue = [...results];
  let current: AlertFetchResult | null = NONE;
  const fetch = vi.fn(async () => {
    if (queue.length > 0) current = queue.shift()!;
    return current;
  });
  const seen: AlertFetchResult[] = [];
  const poller = createAlertsPoller({ fetch, onResult: (r) => seen.push(r) });
  return { poller, fetch, seen };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('alerts poller', () => {
  it('fetches on start, then every poll interval', async () => {
    const { poller, fetch, seen } = setup([NONE]);
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(seen).toEqual([NONE]);
    await vi.advanceTimersByTimeAsync(ALERTS_POLL_MS);
    expect(fetch).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('polls rarely once the endpoint is absent', async () => {
    const { poller, fetch } = setup([{ kind: 'absent' }]);
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(ALERTS_POLL_MS);
    expect(fetch).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(ALERTS_ABSENT_POLL_MS - ALERTS_POLL_MS);
    expect(fetch).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('a cancelled fetch (null) reports nothing and keeps polling', async () => {
    const { poller, fetch, seen } = setup([null]);
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(seen).toEqual([]);
    await vi.advanceTimersByTimeAsync(ALERTS_POLL_MS);
    expect(fetch).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('a rejecting fetch is reported as a typed network failure and polling continues', async () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const fetch = vi.fn(async (): Promise<AlertFetchResult | null> => {
      throw new Error('bug');
    });
    const seen: AlertFetchResult[] = [];
    const poller = createAlertsPoller({ fetch, onResult: (r) => seen.push(r) });
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(seen[0]).toMatchObject({ kind: 'failed', error: { name: 'AlertFetchError', kind: 'network' } });
    await vi.advanceTimersByTimeAsync(ALERTS_POLL_MS);
    expect(fetch).toHaveBeenCalledTimes(2);
    err.mockRestore();
    poller.stop();
  });

  it('pause stops polling; resume fetches at once', async () => {
    const { poller, fetch } = setup([NONE]);
    poller.start();
    await vi.advanceTimersByTimeAsync(0);
    poller.pause();
    await vi.advanceTimersByTimeAsync(ALERTS_POLL_MS * 3);
    expect(fetch).toHaveBeenCalledTimes(1);
    poller.resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('pollNow does not overlap an in-flight fetch', async () => {
    let resolve: (r: AlertFetchResult) => void = () => undefined;
    const fetch = vi.fn(() => new Promise<AlertFetchResult | null>((r) => (resolve = r)));
    const poller = createAlertsPoller({ fetch, onResult: () => undefined });
    poller.start();
    poller.pollNow();
    expect(fetch).toHaveBeenCalledTimes(1);
    resolve(NONE);
    await vi.advanceTimersByTimeAsync(0);
    poller.pollNow();
    expect(fetch).toHaveBeenCalledTimes(2);
    poller.stop();
  });

  it('reports nothing after stop', async () => {
    let resolve: (r: AlertFetchResult) => void = () => undefined;
    const fetch = vi.fn(() => new Promise<AlertFetchResult | null>((r) => (resolve = r)));
    const seen: AlertFetchResult[] = [];
    const poller = createAlertsPoller({ fetch, onResult: (r) => seen.push(r) });
    poller.start();
    poller.stop();
    resolve(NONE);
    await vi.advanceTimersByTimeAsync(ALERTS_POLL_MS * 2);
    expect(seen).toEqual([]);
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
