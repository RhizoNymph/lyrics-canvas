import { describe, it, expect } from 'vitest';
import axios, { AxiosError, AxiosHeaders } from 'axios';
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import { installHealthInterceptors } from './interceptors';
import type { HealthObservation } from './types';

/** An axios instance whose transport answers with `status`, or fails like a dropped connection when null. */
function instanceAnswering(status: number | null) {
  const adapter: AxiosAdapter = (config: InternalAxiosRequestConfig) => {
    if (status === null) return Promise.reject(new AxiosError('Network Error', 'ERR_NETWORK', config));
    const response = { status, statusText: '', headers: new AxiosHeaders(), config, data: { detail: 'x' } };
    if (status >= 200 && status < 300) return Promise.resolve(response);
    return Promise.reject(new AxiosError('fail', 'ERR_BAD_RESPONSE', config, undefined, response));
  };
  const instance = axios.create({ adapter });
  const seen: HealthObservation[] = [];
  installHealthInterceptors(instance, (o) => seen.push(o), () => true);
  return { instance, seen };
}

describe('installHealthInterceptors', () => {
  it.each([502, 503, 504])('a %i from an API call reports down and still rejects with the same error', async (status) => {
    const { instance, seen } = instanceAnswering(status);
    const err = await instance.get('/api/canvases').then(
      () => null,
      (e: unknown) => e
    );
    expect(axios.isAxiosError(err) && err.response?.status).toBe(status);
    expect(seen).toEqual([{ kind: 'down', cause: { type: 'http', status } }]);
  });

  it('a network error reports unreachable and still rejects', async () => {
    const { instance, seen } = instanceAnswering(null);
    await expect(instance.get('/api/canvases')).rejects.toMatchObject({ code: 'ERR_NETWORK' });
    expect(seen).toEqual([{ kind: 'unreachable', reason: 'network' }]);
  });

  it('a success reports ok and resolves unchanged', async () => {
    const { instance, seen } = instanceAnswering(200);
    const res = await instance.get('/api/canvases');
    expect(res.data).toEqual({ detail: 'x' });
    expect(seen).toEqual([{ kind: 'ok' }]);
  });

  it('a 4xx proves the backend is up (ok) and still rejects', async () => {
    const { instance, seen } = instanceAnswering(404);
    await expect(instance.get('/api/x')).rejects.toBeInstanceOf(AxiosError);
    expect(seen).toEqual([{ kind: 'ok' }]);
  });

  it('a 500 reports nothing and still rejects', async () => {
    const { instance, seen } = instanceAnswering(500);
    await expect(instance.get('/api/x')).rejects.toBeInstanceOf(AxiosError);
    expect(seen).toEqual([]);
  });

  it('runs after interceptors registered earlier and keeps their behavior', async () => {
    const adapter: AxiosAdapter = (config) =>
      Promise.reject(
        new AxiosError('fail', 'ERR', config, undefined, { status: 401, statusText: '', headers: new AxiosHeaders(), config, data: '' })
      );
    const instance = axios.create({ adapter });
    const calls: string[] = [];
    instance.interceptors.response.use(
      (r) => r,
      (e: unknown) => {
        calls.push('auth');
        return Promise.reject(e);
      }
    );
    installHealthInterceptors(instance, () => calls.push('health'), () => true);
    await expect(instance.get('/api/x')).rejects.toBeInstanceOf(AxiosError);
    expect(calls).toEqual(['auth', 'health']);
  });
});
