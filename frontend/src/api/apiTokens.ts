/**
 * `/api/tokens` (round-3 contract §C): list, create and revoke personal API tokens. Built over an injected
 * axios instance (tests use a mock adapter); `client.ts` binds it to the app instance, whose bearer is the
 * login JWT. Rejects with an `ApiTokenError` only.
 */
import type { AxiosInstance } from 'axios';
import { toApiTokenError } from '@/lib/apiTokens/errors';
import type { ApiToken, ApiTokenCreateRequest, ApiTokenCreateResponse } from '@/types/apiTokens';

export interface ApiTokenApi {
  /** Tokens not revoked, newest first; never secrets. */
  list(signal?: AbortSignal): Promise<ApiToken[]>;
  /** The response holds the only copy of the secret the client will ever see. */
  create(req: ApiTokenCreateRequest): Promise<ApiTokenCreateResponse>;
  revoke(id: string): Promise<void>;
}

async function typed<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (err) {
    throw toApiTokenError(err);
  }
}

export function createApiTokenApi(http: AxiosInstance): ApiTokenApi {
  return {
    list: (signal) => typed(async () => (await http.get<ApiToken[]>('/api/tokens', { signal })).data),
    create: (req) =>
      typed(async () => (await http.post<ApiTokenCreateResponse>('/api/tokens', { name: req.name, expires_in_days: req.expires_in_days })).data),
    revoke: (id) =>
      typed(async () => {
        await http.delete<void>(`/api/tokens/${encodeURIComponent(id)}`);
      }),
  };
}
