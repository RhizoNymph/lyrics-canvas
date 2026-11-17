# Personal API Tokens (backend)

Revocable, per-user bearer tokens for scripts and agents (the `tools/agent/` CLI). A token acts as its
user on every API route, except the token management routes themselves, which need a login JWT.

Clients: web "API tokens" dialog (account menu). Android has no token UI.

## Scope

- Table `api_tokens` (migration 011, both directions) and the `ApiToken` model.
- `POST /api/tokens`, `GET /api/tokens`, `DELETE /api/tokens/{id}` (`app/api/tokens.py`).
- Bearer authentication of `lct_` tokens in `app/api/deps.py`, next to the unchanged JWT path.
- Typed errors `invalid_token_name`, `token_not_found`, `token_management_forbidden`.

## Non-scope

- Scopes or read-only tokens: a token can do everything its user can, except manage tokens.
- Showing a secret again, renaming or extending a token (create a new one and revoke the old).
- Listing revoked tokens or deleting their rows (revoked rows stay for the record).
- Rate limiting per token.
- Web and agent CLI (separate branches; see their docs).

## Data model

`api_tokens` (`app/models/api_token.py`, `ApiToken`):

| column | type | notes |
|---|---|---|
| `id` | uuid pk | |
| `user_id` | uuid fk users ON DELETE CASCADE | indexed (`ix_api_tokens_user_id`) |
| `name` | varchar(64) not null | 1-64 code points after trim |
| `token_hash` | char(64) not null | sha256 hex of the full token; unique (`uq_api_tokens_token_hash`) |
| `prefix` | varchar(12) not null | first 12 chars of the token (`lct_` + 8), for display |
| `created_at` | timestamptz not null | default now(); set from the DB clock on create |
| `last_used_at` | timestamptz null | written at most once per minute per token |
| `expires_at` | timestamptz null | null = never |
| `revoked_at` | timestamptz null | set by DELETE |

### Migration 011

`backend/alembic/versions/011_api_tokens.py` (revises 010). Upgrade creates the table, its unique
constraint and the `user_id` index. Downgrade drops the index and the table (every token stops working).

## Token format

`lct_` + `secrets.token_urlsafe(32)` (43 URL-safe chars): 47 chars, 256 bits of randomness. Only
`sha256(token)` is stored; a plain fast hash is enough because the secret is random (no offline
guessing), and it lets authentication look the token up by an indexed equality. The full token is
returned once, in the create response.

## API

All three routes depend on `require_login`; a request authenticated with an API token gets
`403 {"detail": {"error": "token_management_forbidden", "message": ...}}`, so a leaked token cannot
mint new tokens or revoke the user's other tokens.

```jsonc
// POST /api/tokens
{ "name": "laptop agent",        // trimmed, 1-64 code points, else 422 invalid_token_name
  "expires_in_days": 90 }         // 1..365 (pydantic 422 otherwise); omitted = 90; null = never
// 201
{ "token": { "id": "uuid", "name": "laptop agent", "prefix": "lct_AbCdEfGh",
             "created_at": "2026-11-14 10:00:00.123456+00:00", "last_used_at": null,
             "expires_at": "2027-02-12 10:00:00.123456+00:00" },
  "secret": "lct_…" }             // only here, only once

// GET /api/tokens -> 200 [token, …]   not revoked (expired included), newest first, no secrets
// DELETE /api/tokens/{id} -> 204      sets revoked_at
```

Timestamps are `str(datetime)` like every other timestamp of the API.

### Errors

`{"detail": {"error": <code>, "message": <text>}}` (`ApiTokenErrorResponse` in the OpenAPI document):

| code | status | when |
|---|---|---|
| `invalid_token_name` | 422 | name empty after trim or over 64 code points |
| `token_not_found` | 404 | DELETE of an id that is not the caller's, does not exist or is already revoked |
| `token_management_forbidden` | 403 | any `/api/tokens` route called with an API token |

## Authentication

`app/api/deps.py`:

```
Authorization: Bearer <credential>
  │  HTTPBearer
  ▼
get_auth ─ credential starts with "lct_"? ─ yes → api_tokens.authenticate(db, credential)
  │                                              sha256 → SELECT token JOIN user by token_hash
  │                                              live? (revoked_at null, expires_at null or > now())
  │                                              last_used_at null or ≥ 1 min old →
  │                                                 conditional UPDATE … WHERE last_used_at is null
  │                                                 or ≤ now() - 1 min; COMMIT
  │                                              debug log auth.api_token user_id=… token_id=…
  │                                              → Auth(user, api_token, token_id) | 401 "Invalid token"
  └ no → decode_access_token → get_user_by_id → Auth(user, login) | 401 (unchanged)
get_current_user(auth) → auth.user                 (every existing route)
require_login(auth)    → auth.user | 403 token_management_forbidden   (/api/tokens)
```

`get_current_user` keeps its name and return type, so every route and every test override of it
works as before. FastAPI caches `get_auth` per request, so a route depending on both resolves the
bearer once. The `last_used_at` write commits inside the dependency; the request's later work runs in
a fresh transaction of the same session (`expire_on_commit=False`, so the loaded user stays usable).

## Files

| file | role | key exports |
|---|---|---|
| `backend/alembic/versions/011_api_tokens.py` | the migration | |
| `backend/app/models/api_token.py` | ORM model | `ApiToken`, `NAME_MAX_LENGTH`, `PREFIX_LENGTH`, `HASH_LENGTH` |
| `backend/app/services/api_tokens/errors.py` | typed errors | `TokenErrorCode`, `TokenError`, `InvalidTokenNameError`, `TokenNotFoundError`, `TokenManagementForbiddenError` |
| `backend/app/services/api_tokens/secrets.py` | format, hash, prefix | `TOKEN_PREFIX`, `TOKEN_LENGTH`, `new_secret`, `NewSecret`, `hash_token`, `display_prefix`, `is_api_token` |
| `backend/app/services/api_tokens/values.py` | pure rules | `token_name`, `expires_at`, `is_live`, `needs_touch`, `DEFAULT/MIN/MAX_EXPIRES_IN_DAYS`, `LAST_USED_RESOLUTION` |
| `backend/app/services/api_tokens/service.py` | DB operations | `create_token`, `list_tokens`, `revoke_token`, `authenticate`, `CreatedToken`, `TokenAuth` |
| `backend/app/schemas/api_token.py` | wire models | `ApiTokenCreate`, `ApiTokenResponse`, `ApiTokenCreatedResponse`, `ApiTokenErrorBody`, `ApiTokenErrorResponse` |
| `backend/app/api/tokens.py` | routes | `router`, `token_http_error` |
| `backend/app/api/deps.py` | authentication | `get_auth`, `Auth`, `AuthMethod`, `get_current_user`, `require_login` |
| `backend/tests/api_tokens/` | pure, route + auth (real auth, DB) and migration tests | |

## Invariants and constraints

- The secret is never stored, logged or returned after the create response; logs carry `user_id` and
  `token_id` only.
- A token authenticates only while `revoked_at` is null and `expires_at` is null or in the future
  (database clock); otherwise 401 `Invalid token`, exactly like a bad JWT.
- Token management (`/api/tokens`) is only reachable with a login JWT.
- `last_used_at` changes at most once per minute per token, also under concurrent requests.
- Deleting a user deletes their tokens (FK cascade).
- A bearer that does not start with `lct_` takes the JWT path, unchanged.

## Decisions

- `token_management_forbidden` carries a `message` besides `error` (the contract shows only `error`),
  matching every other typed error body of the API.
- `expires_in_days` out of 1..365 is pydantic's generic 422 (the contract names no code for it).
- Expired, not revoked tokens stay in `GET /api/tokens` so the user sees why a script stopped working.
