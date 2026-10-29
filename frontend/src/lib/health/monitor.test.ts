import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createHealthMonitor } from './monitor';
import { DOWN_BACKOFF_BASE_MS, HEALTHY_PROBE_MS, RECOVERY_DISPLAY_MS, SUSPECT_PROBE_MS } from './machine';
import type { HealthObservation, HealthState } from './types';

const OK: HealthObservation = { kind: 'ok' };
const DOWN: HealthObservation = { kind: 'down', cause: { type: 'http', status: 502 } };
const NET: HealthObservation = { kind: 'unreachable', reason: 'network' };

function setup(results: HealthObservation[]) {
  let current: HealthObservation = OK;
  const queue = [...results];
  const probe = vi.fn(async () => {
    if (queue.length > 0) current = queue.shift()!;
    return current;
  });
  const states: HealthState[] = [];
  const monitor = createHealthMonitor({ probe, now: () => Date.now(), onChange: (s) => states.push(s) });
  return { monitor, probe, states, push: (o: HealthObservation) => queue.push(o), last: () => monitor.state() };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(1_000_000);
});
afterEach(() => vi.useRealTimers());

describe('health monitor', () => {
  it('probes immediately on start, then every healthy interval', async () => {
    const { monitor, probe } = setup([OK]);
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(probe).toHaveBeenCalledTimes(1);
    expect(monitor.state()).toEqual({ phase: 'ok' });
    await vi.advanceTimersByTimeAsync(HEALTHY_PROBE_MS - 1);
    expect(probe).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(probe).toHaveBeenCalledTimes(2);
    monitor.stop();
  });

  it('confirms down after a quick second probe and then backs off', async () => {
    const { monitor, probe } = setup([OK, DOWN, DOWN, DOWN]);
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(HEALTHY_PROBE_MS);
    expect(monitor.state().phase).toBe('suspect');
    await vi.advanceTimersByTimeAsync(SUSPECT_PROBE_MS);
    expect(monitor.state()).toMatchObject({ phase: 'failing', problem: DOWN });
    expect(probe).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(DOWN_BACKOFF_BASE_MS);
    expect(probe).toHaveBeenCalledTimes(4);
    // next delay doubled
    await vi.advanceTimersByTimeAsync(DOWN_BACKOFF_BASE_MS);
    expect(probe).toHaveBeenCalledTimes(4);
    await vi.advanceTimersByTimeAsync(DOWN_BACKOFF_BASE_MS);
    expect(probe).toHaveBeenCalledTimes(5);
    monitor.stop();
  });

  it('shows "back online" on recovery and settles after the display time', async () => {
    const { monitor } = setup([DOWN, DOWN, OK]);
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(SUSPECT_PROBE_MS);
    expect(monitor.state().phase).toBe('failing');
    await vi.advanceTimersByTimeAsync(DOWN_BACKOFF_BASE_MS);
    expect(monitor.state().phase).toBe('recovered');
    await vi.advanceTimersByTimeAsync(RECOVERY_DISPLAY_MS);
    expect(monitor.state()).toEqual({ phase: 'ok' });
    monitor.stop();
  });

  it('a passive failure pulls the next probe forward to confirm', async () => {
    const { monitor, probe, push } = setup([OK]);
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    push(DOWN);
    monitor.observe(NET, 'passive');
    expect(monitor.state().phase).toBe('suspect');
    await vi.advanceTimersByTimeAsync(SUSPECT_PROBE_MS);
    expect(probe).toHaveBeenCalledTimes(2);
    expect(monitor.state()).toMatchObject({ phase: 'failing', problem: DOWN });
    monitor.stop();
  });

  it('a passive success while failing recovers without waiting for the probe', async () => {
    const { monitor } = setup([DOWN, DOWN]);
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(SUSPECT_PROBE_MS);
    expect(monitor.state().phase).toBe('failing');
    monitor.observe(OK, 'passive');
    expect(monitor.state().phase).toBe('recovered');
    monitor.stop();
  });

  it('pause stops polling; resume probes immediately', async () => {
    const { monitor, probe } = setup([OK]);
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    monitor.pause();
    await vi.advanceTimersByTimeAsync(HEALTHY_PROBE_MS * 3);
    expect(probe).toHaveBeenCalledTimes(1);
    monitor.resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(probe).toHaveBeenCalledTimes(2);
    monitor.stop();
  });

  it('probeNow runs at once and does not overlap an in-flight probe', async () => {
    let resolve: (o: HealthObservation) => void = () => undefined;
    const probe = vi.fn(() => new Promise<HealthObservation>((r) => (resolve = r)));
    const monitor = createHealthMonitor({ probe, now: () => Date.now(), onChange: () => undefined });
    monitor.start();
    monitor.probeNow();
    monitor.probeNow();
    expect(probe).toHaveBeenCalledTimes(1);
    resolve(OK);
    await vi.advanceTimersByTimeAsync(0);
    monitor.probeNow();
    expect(probe).toHaveBeenCalledTimes(2);
    monitor.stop();
  });

  it('browserOffline confirms at once', async () => {
    const { monitor } = setup([OK]);
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    monitor.browserOffline();
    expect(monitor.state()).toMatchObject({ phase: 'failing', problem: { kind: 'unreachable', reason: 'offline' } });
    monitor.stop();
  });

  it('a probe that yields no signal (cancelled) leaves the state alone and keeps polling', async () => {
    const probe = vi.fn(async (): Promise<HealthObservation | null> => null);
    const monitor = createHealthMonitor({ probe, now: () => Date.now(), onChange: () => undefined });
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(monitor.state()).toEqual({ phase: 'unknown' });
    await vi.advanceTimersByTimeAsync(HEALTHY_PROBE_MS);
    expect(probe).toHaveBeenCalledTimes(2);
    monitor.stop();
  });

  it('a probe that rejects counts as unreachable instead of breaking the loop', async () => {
    const probe = vi.fn(async (): Promise<HealthObservation> => {
      throw new Error('bug');
    });
    const warn = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const monitor = createHealthMonitor({ probe, now: () => Date.now(), onChange: () => undefined });
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(monitor.state()).toMatchObject({ phase: 'suspect', problem: { kind: 'unreachable' } });
    await vi.advanceTimersByTimeAsync(SUSPECT_PROBE_MS);
    expect(probe).toHaveBeenCalledTimes(2);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
    monitor.stop();
  });

  it('reports every state change and nothing after stop', async () => {
    const { monitor, states } = setup([OK, DOWN]);
    monitor.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(states).toEqual([{ phase: 'ok' }]);
    monitor.stop();
    await vi.advanceTimersByTimeAsync(HEALTHY_PROBE_MS * 2);
    monitor.observe(DOWN, 'passive');
    expect(states).toEqual([{ phase: 'ok' }]);
  });
});
