# Web API Tokens

The canvas list page's account area has an **API tokens** button that opens a dialog for the user's
personal API tokens (round-3 contract §C): list them, create one (shown once), and revoke one. The tokens
let scripts and agents (the `lcx` CLI and its Claude Code skill) call the API as the user.

## Scope

- Typed wire models and a typed API module for `GET /api/tokens`, `POST /api/tokens`,
  `DELETE /api/tokens/{id}`, rejecting only with `ApiTokenError`.
- A zustand store (`apiTokenStore`) holding the list, the create state, the one-time secret and the revoke
  state; actions resolve to `ApiTokenResult` and never throw.
- The dialog: list (name, `prefix…`, created, last used, expires; expired tokens marked), create form
  (name + expiry 30 days / 90 days / 1 year / Never, default 90 days), one-time secret panel with a copy
  button and a warning, revoke with an inline confirmation.
- The "API tokens" button next to "Voice dataset" and "Sign Out" on the canvas list page header.

## Non-scope

- The backend (table, token auth in `deps.py`, `token_management_forbidden`): the backend branch and its
  doc.
- Android: no token UI (contract).
- Renaming tokens, changing expiry after creation, showing revoked tokens, showing a secret again.
- Using an API token as the web app's own credential: the web app always sends the login JWT.

## Data and control flow

1. `CanvasListPage` header button sets `tokensOpen`; `ApiTokensDialog` mounts.
2. On mount the dialog calls `useApiTokenStore.getState().load()`: `apiTokenApi.list()` ->
   `GET /api/tokens` (bearer = login JWT from `localStorage`, added by the shared axios instance). The
   result is sorted newest first (`sortTokens`, id tie-break) and stored with `loadState: 'ready'`; a
   failure stores `loadState: 'failed'` + the typed `loadError`, shown with a Retry button. A load sequence
   number drops stale responses.
3. Create: the dialog keeps the name and expiry choice in local state. Submit calls
   `store.create(name, expiry)`: `checkTokenName` trims and checks 1..64 code points locally
   (`invalid_token_name` without a request), then `POST /api/tokens {name, expires_in_days}` (`null` =
   never). On success the token goes on top of the list (`addToken`) and the response (token + secret) is
   kept in `revealed`; the form is replaced by the secret panel and reset to name "" / 90 days. A second
   create while one is saving is refused. A failure is `createState: {kind: 'failed', error}`, shown under
   the form and cleared on the next name edit.
4. Copy: `copyToClipboard(secret, navigator.clipboard)` -> `'copied' | 'failed'` shown on the button
   ("Copied" / "Copy failed"; the secret is also `user-select: all` for manual copying). "Done" calls
   `dismissSecret()` and the form comes back.
5. Revoke: the row's Revoke button sets the dialog's `confirmingId`; the row then shows
   "Revoke <name>? Anything using it stops working." with Cancel / Revoke. Confirm calls `store.revoke(id)`:
   `DELETE /api/tokens/{id}` (id URL-encoded). 204 or `token_not_found` (already gone) removes the row and
   drops `revealed` if it was that token; other failures keep the row and set `revokeError` for that row.
   One revoke at a time.
6. Close (Close button, backdrop press, Escape when no confirmation is pending; Escape first cancels a
   pending confirmation): `store.reset()` forgets the secret, the create error and the revoke error; the
   list stays cached and is reloaded on the next open. Unmount also calls `reset()`.
7. A 401 goes through the shared axios interceptor (sign-out redirect) like every other call; a 403
   `token_management_forbidden` (only possible if the bearer were an API token) is shown with its own text.

## Files

| File | Role | Key exports |
|---|---|---|
| `frontend/src/types/apiTokens.ts` | Wire shapes | `ApiToken`, `ApiTokenCreateRequest`, `ApiTokenCreateResponse` |
| `frontend/src/lib/apiTokens/errors.ts` | Typed failures, user-facing lines | `ApiTokenError`, `ApiTokenErrorKind`, `toApiTokenError`, `describeApiTokenError` |
| `frontend/src/lib/apiTokens/tokens.ts` | Name check, expiry choices, row labels, list updates | `checkTokenName`, `MAX_TOKEN_NAME_LENGTH`, `EXPIRY_CHOICES`, `ExpiryDays`, `DEFAULT_EXPIRY`, `expiryValue`, `expiryFromValue`, `formatTokenRow`, `sortTokens`, `addToken`, `removeToken` |
| `frontend/src/lib/apiTokens/clipboard.ts` | Never-throwing clipboard write | `copyToClipboard`, `ClipboardWriter` |
| `frontend/src/lib/apiTokens/index.ts` | Barrel | |
| `frontend/src/api/apiTokens.ts` | `/api/tokens` over an injected axios instance | `createApiTokenApi`, `ApiTokenApi` |
| `frontend/src/api/client.ts` | Binds it to the app instance | `apiTokenApi` |
| `frontend/src/stores/apiTokenStore.ts` | List, create, one-time secret, revoke | `useApiTokenStore`, `ApiTokenState`, `ApiTokenResult`, `CreateState`, `ApiTokenLoadState`, `INITIAL_API_TOKEN_STATE` |
| `frontend/src/components/account/ApiTokensView.tsx` | Pure dialog view | `ApiTokensView` (default), `ApiTokensViewProps`, `CopyState` |
| `frontend/src/components/account/ApiTokensDialog.tsx` | Connected dialog (store, focus trap, Escape, clipboard) | `ApiTokensDialog` (default) |
| `frontend/src/components/pages/CanvasListPage.tsx` | "API tokens" header button | |

Tests: `lib/apiTokens/errors.test.ts`, `tokens.test.ts`, `clipboard.test.ts`, `api/apiTokens.test.ts`
(mock axios adapter), `stores/apiTokenStore.test.ts` (mocked `@/api/client`),
`components/account/ApiTokensView.test.tsx` (static markup).

## Invariants and constraints

- The secret exists on the client only in `apiTokenStore.revealed` (memory) from the create response
  until Done, close, or revoking that token. It is never written to storage, never logged (logs carry
  `token_id`, `prefix`, `kind`, `status` only), never shown again.
- The list never holds revoked tokens; after a successful create or revoke the list matches the server
  without a reload.
- Names sent to the server are trimmed and 1..64 code points; the input also caps at 64 UTF-16 units.
- `expires_in_days` is one of 30, 90, 365 or `null`; the default is 90.
- At most one create and one revoke run at a time.
- Store actions never throw; components read typed results.

## Decisions

- **Where "Account" lives**: the app has no account menu, only the canvas list header (user name,
  "Voice dataset", "Sign Out"). The "API tokens" button sits there, next to "Voice dataset", as the
  account area; no new menu component.
- **Revoke of an already-gone token** (`token_not_found`) counts as success and removes the row: the
  user's intent (the token no longer works) holds.
- **Inline revoke confirmation** in the row instead of `window.confirm`, so it is testable and themed.
- **Expired tokens** are still listed by the server (only revoked ones are hidden), so the row shows
  "Expired <date>" in red.
- Code written against the contract's wire shapes; the backend branch (`feat/api-tokens-backend`) had not
  pushed when this branch was opened, so no schema comparison was possible.
