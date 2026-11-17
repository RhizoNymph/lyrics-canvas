/**
 * Wire shapes of `/api/tokens` (round-3 contract §C): personal API tokens. The secret comes back only once,
 * in the create response; list and token objects never carry it.
 */

/** One token as the server lists it (timestamps are ISO 8601 strings). */
export interface ApiToken {
  readonly id: string;
  readonly name: string;
  /** The first 12 characters of the secret (`lct_` + 8), for recognising it. */
  readonly prefix: string;
  readonly created_at: string;
  readonly last_used_at: string | null;
  /** null = never expires. */
  readonly expires_at: string | null;
}

/** `POST /api/tokens`. `expires_in_days` is 1..365, or null for a token that never expires. */
export interface ApiTokenCreateRequest {
  readonly name: string;
  readonly expires_in_days: number | null;
}

/** `201` body of `POST /api/tokens`: the token plus its full secret (`lct_…`), shown once. */
export interface ApiTokenCreateResponse {
  readonly token: ApiToken;
  readonly secret: string;
}
