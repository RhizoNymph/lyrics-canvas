import { describe, it, expect } from 'vitest';
import axios, { AxiosError } from 'axios';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { createApiTokenApi } from './apiTokens';
import { ApiTokenError } from '@/lib/apiTokens';
import type { ApiToken, ApiTokenCreateResponse } from '@/types/apiTokens';

function harness(respond: (config: InternalAxiosRequestConfig) => { status: number; data: unknown } | 'network') {
  const seen: { method?: string; url?: string; data: unknown }[] = [];
  const adapter: AxiosAdapter = async (config) => {
    seen.push({ method: config.method, url: config.url, data: config.data === undefined ? undefined : JSON.parse(config.data as string) });
    const r = respond(config);
    if (r === 'network') throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config);
    const response: AxiosResponse = { status: r.status, statusText: '', data: r.data, headers: {}, config };
    if (r.status >= 400) throw new AxiosError('Request failed', AxiosError.ERR_BAD_RESPONSE, config, {}, response);
    return response;
  };
  return { api: createApiTokenApi(axios.create({ adapter })), seen };
}

const token: ApiToken = {
  id: 't1', name: 'laptop', prefix: 'lct_abcdefgh', created_at: '2026-11-14T00:00:00Z', last_used_at: null,
  expires_at: '2027-02-12T00:00:00Z',
};

describe('apiTokenApi', () => {
  it('lists with GET /api/tokens', async () => {
    const { api, seen } = harness(() => ({ status: 200, data: [token] }));
    await expect(api.list()).resolves.toEqual([token]);
    expect(seen).toEqual([{ method: 'get', url: '/api/tokens', data: undefined }]);
  });

  it('creates with POST /api/tokens {name, expires_in_days}, null for never', async () => {
    const body: ApiTokenCreateResponse = { token, secret: 'lct_' + 'x'.repeat(43) };
    const { api, seen } = harness(() => ({ status: 201, data: body }));
    await expect(api.create({ name: 'laptop', expires_in_days: 90 })).resolves.toEqual(body);
    await api.create({ name: 'ci', expires_in_days: null });
    expect(seen).toEqual([
      { method: 'post', url: '/api/tokens', data: { name: 'laptop', expires_in_days: 90 } },
      { method: 'post', url: '/api/tokens', data: { name: 'ci', expires_in_days: null } },
    ]);
  });

  it('revokes with DELETE /api/tokens/{id}, encoding the id', async () => {
    const { api, seen } = harness(() => ({ status: 204, data: '' }));
    await expect(api.revoke('a/b')).resolves.toBeUndefined();
    expect(seen).toEqual([{ method: 'delete', url: '/api/tokens/a%2Fb', data: undefined }]);
  });

  it('rejects with typed errors', async () => {
    const forbidden = harness(() => ({ status: 403, data: { detail: { error: 'token_management_forbidden' } } }));
    const err = await forbidden.api.list().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiTokenError);
    expect(err).toMatchObject({ kind: 'token_management_forbidden', status: 403 });

    const badName = harness(() => ({ status: 422, data: { detail: { error: 'invalid_token_name', message: 'too long' } } }));
    await expect(badName.api.create({ name: 'x', expires_in_days: 30 })).rejects.toMatchObject({ kind: 'invalid_token_name' });

    const gone = harness(() => ({ status: 404, data: { detail: { error: 'token_not_found' } } }));
    await expect(gone.api.revoke('t1')).rejects.toMatchObject({ kind: 'token_not_found', status: 404 });

    const down = harness(() => 'network');
    await expect(down.api.list()).rejects.toMatchObject({ kind: 'network', status: null });
  });
});
