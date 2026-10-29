import { describe, it, expect } from 'vitest';
import {
  DOWN_BACKOFF_BASE_MS,
  DOWN_BACKOFF_MAX_MS,
  FAILURE_THRESHOLD,
  HEALTHY_PROBE_MS,
  INITIAL_HEALTH_STATE,
  SUSPECT_PROBE_MS,
  nextProbeDelay,
  transition,
} from './machine';
import type { HealthEvent, HealthObservation, HealthState, ObservationSource } from './types';

const DOWN: HealthObservation = { kind: 'down', cause: { type: 'http', status: 502 } };
const NET: HealthObservation = { kind: 'unreachable', reason: 'network' };
const OK: HealthObservation = { kind: 'ok' };

function obs(observation: HealthObservation, at = 0, source: ObservationSource = 'probe'): HealthEvent {
  return { type: 'observed', source, observation, at };
}

function run(events: HealthEvent[], from: HealthState = INITIAL_HEALTH_STATE): HealthState {
  return events.reduce(transition, from);
}

describe('health machine: debounce', () => {
  it('starts unknown', () => {
    expect(INITIAL_HEALTH_STATE).toEqual({ phase: 'unknown' });
  });

  it('ok while ok keeps the same state object (no needless re-render)', () => {
    const ok: HealthState = { phase: 'ok' };
    expect(transition(ok, obs(OK, 5))).toBe(ok);
    expect(transition(ok, obs(OK, 5, 'passive'))).toBe(ok);
  });

  it('a first ok goes to ok', () => {
    expect(run([obs(OK)])).toEqual({ phase: 'ok' });
  });

  it('one probe failure is only suspect (not shown)', () => {
    expect(run([obs(OK), obs(DOWN, 10)])).toEqual({ phase: 'suspect', problem: DOWN, failures: 1 });
  });

  it('a failure at startup is suspect too', () => {
    expect(run([obs(DOWN)]).phase).toBe('suspect');
  });

  it(`${FAILURE_THRESHOLD} consecutive probe failures confirm, stamped with the confirming time`, () => {
    expect(run([obs(OK), obs(DOWN, 10), obs(DOWN, 20)])).toEqual({
      phase: 'failing',
      problem: DOWN,
      failures: 2,
      since: 20,
    });
  });

  it('a success between failures resets the count', () => {
    expect(run([obs(DOWN, 1), obs(OK, 2), obs(DOWN, 3)])).toEqual({ phase: 'suspect', problem: DOWN, failures: 1 });
  });

  it('a passive failure makes the state suspect', () => {
    expect(run([obs(OK), obs(NET, 1, 'passive')])).toEqual({ phase: 'suspect', problem: NET, failures: 1 });
  });

  it('a burst of passive failures never confirms on its own', () => {
    const s = run([obs(OK), obs(NET, 1, 'passive'), obs(NET, 1, 'passive'), obs(NET, 2, 'passive')]);
    expect(s.phase).toBe('suspect');
  });

  it('a passive failure plus a probe failure confirms', () => {
    expect(run([obs(OK), obs(DOWN, 1, 'passive'), obs(DOWN, 5)]).phase).toBe('failing');
  });

  it('the confirming probe sets the reported problem', () => {
    const s = run([obs(NET, 1, 'passive'), obs(DOWN, 5)]);
    expect(s).toMatchObject({ phase: 'failing', problem: DOWN });
  });
});

describe('health machine: while failing', () => {
  const failing = run([obs(DOWN, 1), obs(DOWN, 2)]);

  it('further probe failures count up and keep the incident time', () => {
    expect(run([obs(DOWN, 10), obs(DOWN, 20)], failing)).toMatchObject({ phase: 'failing', failures: 4, since: 2 });
  });

  it('a changed problem kind is reported but the incident time is kept', () => {
    expect(transition(failing, obs(NET, 9))).toMatchObject({ phase: 'failing', problem: NET, since: 2 });
  });

  it('passive failures do not change anything', () => {
    expect(transition(failing, obs(NET, 9, 'passive'))).toBe(failing);
  });

  it('browser offline confirms immediately, from any phase', () => {
    expect(transition({ phase: 'ok' }, { type: 'browser_offline', at: 7 })).toEqual({
      phase: 'failing',
      problem: { kind: 'unreachable', reason: 'offline' },
      failures: FAILURE_THRESHOLD,
      since: 7,
    });
  });

  it('browser offline while failing keeps the incident', () => {
    expect(transition(failing, { type: 'browser_offline', at: 9 })).toMatchObject({
      problem: { kind: 'unreachable', reason: 'offline' },
      since: 2,
    });
  });
});

describe('health machine: recovery', () => {
  const failing = run([obs(DOWN, 1), obs(DOWN, 2)]);

  it('an ok while failing shows "back online"', () => {
    expect(transition(failing, obs(OK, 30))).toEqual({ phase: 'recovered', at: 30, downSince: 2 });
  });

  it('a passive success recovers too (the backend answered)', () => {
    expect(transition(failing, obs(OK, 30, 'passive')).phase).toBe('recovered');
  });

  it('settle ends the "back online" note', () => {
    expect(run([obs(OK, 30), { type: 'settle' }], failing)).toEqual({ phase: 'ok' });
  });

  it('further oks keep the note until settle', () => {
    const recovered = transition(failing, obs(OK, 30));
    expect(transition(recovered, obs(OK, 31))).toBe(recovered);
  });

  it('settle outside recovered is a no-op', () => {
    expect(transition(failing, { type: 'settle' })).toBe(failing);
    expect(transition({ phase: 'ok' }, { type: 'settle' })).toEqual({ phase: 'ok' });
  });

  it('a suspect that clears never shows "back online"', () => {
    expect(run([obs(OK), obs(DOWN, 1), obs(OK, 2)])).toEqual({ phase: 'ok' });
  });

  it('a failure right after recovery starts a new debounce, then a new incident', () => {
    const recovered = transition(failing, obs(OK, 30));
    const s1 = transition(recovered, obs(DOWN, 40));
    expect(s1.phase).toBe('suspect');
    expect(transition(s1, obs(DOWN, 50))).toMatchObject({ phase: 'failing', since: 50 });
  });
});

describe('nextProbeDelay', () => {
  it('polls at the healthy interval when unknown, ok or recovered', () => {
    expect(nextProbeDelay({ phase: 'unknown' })).toBe(HEALTHY_PROBE_MS);
    expect(nextProbeDelay({ phase: 'ok' })).toBe(HEALTHY_PROBE_MS);
    expect(nextProbeDelay({ phase: 'recovered', at: 0, downSince: 0 })).toBe(HEALTHY_PROBE_MS);
  });

  it('confirms quickly when suspect', () => {
    expect(nextProbeDelay({ phase: 'suspect', problem: DOWN, failures: 1 })).toBe(SUSPECT_PROBE_MS);
    expect(SUSPECT_PROBE_MS).toBeLessThan(HEALTHY_PROBE_MS);
  });

  it('backs off exponentially while failing, capped', () => {
    const at = (failures: number) => nextProbeDelay({ phase: 'failing', problem: DOWN, failures, since: 0 });
    expect(at(2)).toBe(DOWN_BACKOFF_BASE_MS);
    expect(at(3)).toBe(DOWN_BACKOFF_BASE_MS * 2);
    expect(at(4)).toBe(DOWN_BACKOFF_BASE_MS * 4);
    expect(at(50)).toBe(DOWN_BACKOFF_MAX_MS);
    for (let f = 2; f < 20; f++) expect(at(f + 1)).toBeGreaterThanOrEqual(at(f));
  });
});
