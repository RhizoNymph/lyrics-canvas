import { describe, it, expect, vi } from 'vitest';
import { createObservationChannel } from './channel';

describe('observation channel', () => {
  it('delivers published observations to every subscriber', () => {
    const ch = createObservationChannel();
    const a = vi.fn();
    const b = vi.fn();
    ch.subscribe(a);
    ch.subscribe(b);
    ch.publish({ kind: 'ok' });
    expect(a).toHaveBeenCalledWith({ kind: 'ok' });
    expect(b).toHaveBeenCalledWith({ kind: 'ok' });
  });

  it('unsubscribe stops delivery', () => {
    const ch = createObservationChannel();
    const a = vi.fn();
    const off = ch.subscribe(a);
    off();
    ch.publish({ kind: 'ok' });
    expect(a).not.toHaveBeenCalled();
  });

  it('publishing with no subscribers is a no-op', () => {
    expect(() => createObservationChannel().publish({ kind: 'ok' })).not.toThrow();
  });
});
