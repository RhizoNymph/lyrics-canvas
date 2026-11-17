import { create } from 'zustand';
import { apiTokenApi } from '@/api/client';
import { addToken, checkTokenName, removeToken, sortTokens, toApiTokenError } from '@/lib/apiTokens';
import type { ApiTokenError, ExpiryDays } from '@/lib/apiTokens';
import type { ApiToken, ApiTokenCreateResponse } from '@/types/apiTokens';

export type ApiTokenResult<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly error: ApiTokenError };

export type ApiTokenLoadState = 'idle' | 'loading' | 'ready' | 'failed';

export type CreateState = { readonly kind: 'idle' } | { readonly kind: 'saving' } | { readonly kind: 'failed'; readonly error: ApiTokenError };

/**
 * The user's personal API tokens for the "API tokens" dialog. Actions resolve to an ApiTokenResult and
 * never throw. Invariants: the secret of a new token lives only in `revealed` (memory, never persisted
 * or logged) until `dismissSecret` / `reset` / revoking that token; at most one create and one revoke
 * run at a time.
 */
export interface ApiTokenState {
  /** Not revoked, newest first. */
  tokens: readonly ApiToken[];
  loadState: ApiTokenLoadState;
  loadError: ApiTokenError | null;
  createState: CreateState;
  /** The token just created, with its one-time secret. */
  revealed: ApiTokenCreateResponse | null;
  revokingId: string | null;
  revokeError: { readonly id: string; readonly error: ApiTokenError } | null;

  load: () => Promise<ApiTokenResult<void>>;
  create: (rawName: string, expiresInDays: ExpiryDays) => Promise<ApiTokenResult<ApiToken>>;
  revoke: (id: string) => Promise<ApiTokenResult<void>>;
  dismissSecret: () => void;
  clearCreateError: () => void;
  /** Dialog closed: forget the secret and the errors, keep the list. */
  reset: () => void;
}

type Data = Pick<ApiTokenState, 'tokens' | 'loadState' | 'loadError' | 'createState' | 'revealed' | 'revokingId' | 'revokeError'>;

export const INITIAL_API_TOKEN_STATE: Data = {
  tokens: [],
  loadState: 'idle',
  loadError: null,
  createState: { kind: 'idle' },
  revealed: null,
  revokingId: null,
  revokeError: null,
};

let loadSeq = 0;

export const useApiTokenStore = create<ApiTokenState>((set, get) => ({
  ...INITIAL_API_TOKEN_STATE,

  load: async () => {
    const seq = ++loadSeq;
    set({ loadState: 'loading', loadError: null });
    try {
      const tokens = await apiTokenApi.list();
      if (seq === loadSeq) set({ tokens: sortTokens(tokens), loadState: 'ready' });
      return { ok: true, value: undefined };
    } catch (err) {
      const error = toApiTokenError(err);
      console.warn('api tokens: load failed', { kind: error.kind, status: error.status });
      if (seq === loadSeq) set({ loadState: 'failed', loadError: error });
      return { ok: false, error };
    }
  },

  create: async (rawName, expiresInDays) => {
    const busy = get().createState.kind === 'saving';
    const check = checkTokenName(rawName);
    if (busy) return { ok: false, error: toApiTokenError(new Error('A token is already being created')) };
    if (!check.ok) {
      set({ createState: { kind: 'failed', error: check.error } });
      return { ok: false, error: check.error };
    }
    set({ createState: { kind: 'saving' } });
    try {
      const created = await apiTokenApi.create({ name: check.name, expires_in_days: expiresInDays });
      console.info('api tokens: created', { token_id: created.token.id, prefix: created.token.prefix, expires_in_days: expiresInDays });
      set((s) => ({ tokens: addToken(s.tokens, created.token), revealed: created, createState: { kind: 'idle' } }));
      return { ok: true, value: created.token };
    } catch (err) {
      const error = toApiTokenError(err);
      console.warn('api tokens: create failed', { kind: error.kind, status: error.status });
      set({ createState: { kind: 'failed', error } });
      return { ok: false, error };
    }
  },

  revoke: async (id) => {
    if (get().revokingId !== null) return { ok: false, error: toApiTokenError(new Error('A token is already being revoked')) };
    set({ revokingId: id, revokeError: null });
    const gone = () =>
      set((s) => ({
        tokens: removeToken(s.tokens, id),
        revokingId: null,
        revealed: s.revealed?.token.id === id ? null : s.revealed,
      }));
    try {
      await apiTokenApi.revoke(id);
      console.info('api tokens: revoked', { token_id: id });
      gone();
      return { ok: true, value: undefined };
    } catch (err) {
      const error = toApiTokenError(err);
      if (error.kind === 'token_not_found') {
        console.info('api tokens: already gone', { token_id: id });
        gone();
        return { ok: true, value: undefined };
      }
      console.warn('api tokens: revoke failed', { token_id: id, kind: error.kind, status: error.status });
      set({ revokingId: null, revokeError: { id, error } });
      return { ok: false, error };
    }
  },

  dismissSecret: () => set({ revealed: null }),
  clearCreateError: () => {
    if (get().createState.kind === 'failed') set({ createState: { kind: 'idle' } });
  },
  reset: () => set({ revealed: null, createState: { kind: 'idle' }, revokeError: null }),
}));
