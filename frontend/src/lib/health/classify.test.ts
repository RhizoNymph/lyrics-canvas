import { describe, it, expect } from 'vitest';
import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import { observationFromProbeError, observationFromProbeResponse, passiveObservationFromError } from './classify';

function axiosErr(status: number | null, code = 'ERR_NETWORK'): AxiosError {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError(
    'fail',
    status === null ? code : 'ERR_BAD_RESPONSE',
    config,
    undefined,
    status === null ? undefined : { status, statusText: '', headers: {}, config, data: '' }
  );
}

describe('observationFromProbeResponse', () => {
  it('200 {"status":"ok"} is ok', () => {
    expect(observationFromProbeResponse(200, { status: 'ok' })).toEqual({ kind: 'ok' });
  });

  it('200 with another body (SPA fallback html, wrong json) is down: invalid body', () => {
    expect(observationFromProbeResponse(200, '<!DOCTYPE html>')).toEqual({ kind: 'down', cause: { type: 'invalid_body' } });
    expect(observationFromProbeResponse(200, { status: 'degraded' })).toEqual({ kind: 'down', cause: { type: 'invalid_body' } });
    expect(observationFromProbeResponse(200, null)).toEqual({ kind: 'down', cause: { type: 'invalid_body' } });
  });

  it.each([500, 502, 503, 504, 404])('%i is down with that status', (status) => {
    expect(observationFromProbeResponse(status, '')).toEqual({ kind: 'down', cause: { type: 'http', status } });
  });
});

describe('observationFromProbeError', () => {
  it('a network error is unreachable: network when the browser thinks it is online', () => {
    expect(observationFromProbeError(axiosErr(null), true)).toEqual({ kind: 'unreachable', reason: 'network' });
  });

  it('a network error while the browser is offline is unreachable: offline', () => {
    expect(observationFromProbeError(axiosErr(null), false)).toEqual({ kind: 'unreachable', reason: 'offline' });
  });

  it('a timeout is unreachable: timeout', () => {
    expect(observationFromProbeError(axiosErr(null, 'ECONNABORTED'), true)).toEqual({ kind: 'unreachable', reason: 'timeout' });
    expect(observationFromProbeError(axiosErr(null, 'ETIMEDOUT'), true)).toEqual({ kind: 'unreachable', reason: 'timeout' });
  });

  it('an error carrying a response is classified by that response', () => {
    expect(observationFromProbeError(axiosErr(502), true)).toEqual({ kind: 'down', cause: { type: 'http', status: 502 } });
  });

  it('a cancelled probe or a non-axios throwable is not a health signal', () => {
    expect(observationFromProbeError(new CanceledError(), true)).toBeNull();
    expect(observationFromProbeError(new Error('x'), true)).toBeNull();
  });
});

describe('passiveObservationFromError', () => {
  it.each([502, 503, 504])('%i from an API call is down', (status) => {
    expect(passiveObservationFromError(axiosErr(status), true)).toEqual({ kind: 'down', cause: { type: 'http', status } });
  });

  it('a network error from an API call is unreachable', () => {
    expect(passiveObservationFromError(axiosErr(null), true)).toEqual({ kind: 'unreachable', reason: 'network' });
    expect(passiveObservationFromError(axiosErr(null), false)).toEqual({ kind: 'unreachable', reason: 'offline' });
  });

  it.each([400, 401, 404, 409, 422])('%i proves the backend answered (ok)', (status) => {
    expect(passiveObservationFromError(axiosErr(status), true)).toEqual({ kind: 'ok' });
  });

  it('a 500 is ambiguous (backend bug, or the Vite dev proxy failing to connect) and is no signal', () => {
    expect(passiveObservationFromError(axiosErr(500), true)).toBeNull();
  });

  it('cancellations and non-axios errors are ignored', () => {
    expect(passiveObservationFromError(new CanceledError(), true)).toBeNull();
    expect(passiveObservationFromError('boom', true)).toBeNull();
  });
});
