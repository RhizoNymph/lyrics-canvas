import { describe, it, expect, vi, beforeEach } from 'vitest';
import { apiTokenApi } from '@/api/client';
import { INITIAL_API_TOKEN_STATE, useApiTokenStore } from './apiTokenStore';
import { ApiTokenError } from '@/lib/apiTokens';
import type { ApiToken } from '@/types/apiTokens';

vi.mock('@/api/client', () => ({
  apiTokenApi: { list: vi.fn(), create: vi.fn(), revoke: vi.fn() },
}));

const api = vi.mocked(apiTokenApi);

const tok = (id: string, created_at = '2026-11-01T00:00:00Z'): ApiToken => ({
  id, name: `n-${id}`, prefix: `lct_${id.padEnd(8, '0')}`, created_at, last_used_at: null, expires_at: null,
});
const SECRET = 'lct_' + 's'.repeat(43);

function state() {
  return useApiTokenStore.getState();
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(console, 'info').mockImplementation(() => undefined);
  useApiTokenStore.setState(INITIAL_API_TOKEN_STATE);
});

describe('load', () => {
  it('loads and sorts newest first', async () => {
    api.list.mockResolvedValue([tok('a'), tok('b', '2026-11-05T00:00:00Z')]);
    const p = state().load();
    expect(state().loadState).toBe('loading');
    await expect(p).resolves.toEqual({ ok: true, value: undefined });
    expect(state().loadState).toBe('ready');
    expect(state().tokens.map((t) => t.id)).toEqual(['b', 'a']);
    expect(state().loadError).toBeNull();
  });

  it('records a typed failure', async () => {
    const error = new ApiTokenError('token_management_forbidden', 'x', 403);
    api.list.mockRejectedValue(error);
    await expect(state().load()).resolves.toEqual({ ok: false, error });
    expect(state()).toMatchObject({ loadState: 'failed', loadError: error });
  });

  it('a stale load does not overwrite a newer one', async () => {
    let resolveFirst: (v: ApiToken[]) => void = () => undefined;
    api.list.mockImplementationOnce(() => new Promise((r) => { resolveFirst = r; }));
    api.list.mockResolvedValueOnce([tok('new')]);
    const first = state().load();
    await state().load();
    resolveFirst([tok('old')]);
    await first;
    expect(state().tokens.map((t) => t.id)).toEqual(['new']);
  });
});

describe('create', () => {
  it('validates the name locally without calling the server', async () => {
    const r = await state().create('   ', 90);
    expect(r.ok).toBe(false);
    expect(api.create).not.toHaveBeenCalled();
    expect(state().createState).toMatchObject({ kind: 'failed', error: { kind: 'invalid_token_name' } });
  });

  it('posts the trimmed name and expiry, adds the token on top and reveals the secret once', async () => {
    useApiTokenStore.setState({ tokens: [tok('a')], loadState: 'ready' });
    const created = tok('b', '2026-11-14T00:00:00Z');
    api.create.mockResolvedValue({ token: created, secret: SECRET });
    const p = state().create('  laptop  ', null);
    expect(state().createState).toEqual({ kind: 'saving' });
    const r = await p;
    expect(api.create).toHaveBeenCalledWith({ name: 'laptop', expires_in_days: null });
    expect(r).toEqual({ ok: true, value: created });
    expect(state().tokens.map((t) => t.id)).toEqual(['b', 'a']);
    expect(state().revealed).toEqual({ token: created, secret: SECRET });
    expect(state().createState).toEqual({ kind: 'idle' });
  });

  it('never logs the secret', async () => {
    const info = vi.mocked(console.info);
    api.create.mockResolvedValue({ token: tok('b'), secret: SECRET });
    await state().create('x', 30);
    for (const call of info.mock.calls) expect(JSON.stringify(call)).not.toContain(SECRET);
  });

  it('ignores a second create while one is saving', async () => {
    api.create.mockImplementation(() => new Promise(() => undefined));
    void state().create('one', 90);
    const second = await state().create('two', 90);
    expect(second.ok).toBe(false);
    expect(api.create).toHaveBeenCalledTimes(1);
  });

  it('records a server refusal', async () => {
    const error = new ApiTokenError('invalid_token_name', 'bad', 422);
    api.create.mockRejectedValue(error);
    await expect(state().create('ok name', 90)).resolves.toEqual({ ok: false, error });
    expect(state().createState).toEqual({ kind: 'failed', error });
    expect(state().revealed).toBeNull();
  });

  it('dismissSecret forgets the secret but keeps the token listed', async () => {
    api.create.mockResolvedValue({ token: tok('b'), secret: SECRET });
    await state().create('x', 90);
    state().dismissSecret();
    expect(state().revealed).toBeNull();
    expect(state().tokens.map((t) => t.id)).toEqual(['b']);
  });

  it('clearCreateError resets a failed create', async () => {
    await state().create('', 90);
    state().clearCreateError();
    expect(state().createState).toEqual({ kind: 'idle' });
  });
});

describe('revoke', () => {
  beforeEach(() => useApiTokenStore.setState({ tokens: [tok('a'), tok('b')], loadState: 'ready' }));

  it('deletes and removes the row', async () => {
    api.revoke.mockResolvedValue(undefined);
    const p = state().revoke('a');
    expect(state().revokingId).toBe('a');
    await expect(p).resolves.toEqual({ ok: true, value: undefined });
    expect(api.revoke).toHaveBeenCalledWith('a');
    expect(state().tokens.map((t) => t.id)).toEqual(['b']);
    expect(state().revokingId).toBeNull();
  });

  it('treats token_not_found as already revoked', async () => {
    api.revoke.mockRejectedValue(new ApiTokenError('token_not_found', 'x', 404));
    await expect(state().revoke('a')).resolves.toEqual({ ok: true, value: undefined });
    expect(state().tokens.map((t) => t.id)).toEqual(['b']);
    expect(state().revokeError).toBeNull();
  });

  it('keeps the row and records other failures', async () => {
    const error = new ApiTokenError('network', 'x');
    api.revoke.mockRejectedValue(error);
    await expect(state().revoke('a')).resolves.toEqual({ ok: false, error });
    expect(state().tokens.map((t) => t.id)).toEqual(['a', 'b']);
    expect(state().revokeError).toEqual({ id: 'a', error });
    expect(state().revokingId).toBeNull();
  });

  it('drops the revealed secret when its token is revoked', async () => {
    useApiTokenStore.setState({ revealed: { token: tok('a'), secret: SECRET } });
    api.revoke.mockResolvedValue(undefined);
    await state().revoke('a');
    expect(state().revealed).toBeNull();
  });

  it('refuses a second revoke while one runs', async () => {
    api.revoke.mockImplementation(() => new Promise(() => undefined));
    void state().revoke('a');
    expect((await state().revoke('b')).ok).toBe(false);
    expect(api.revoke).toHaveBeenCalledTimes(1);
  });
});

describe('reset', () => {
  it('forgets the secret and errors (closing the dialog), keeping the list', async () => {
    useApiTokenStore.setState({
      tokens: [tok('a')], loadState: 'ready', revealed: { token: tok('a'), secret: SECRET },
      createState: { kind: 'failed', error: new ApiTokenError('network', '') },
      revokeError: { id: 'a', error: new ApiTokenError('network', '') },
    });
    state().reset();
    expect(state()).toMatchObject({ revealed: null, createState: { kind: 'idle' }, revokeError: null });
    expect(state().tokens).toHaveLength(1);
  });
});
