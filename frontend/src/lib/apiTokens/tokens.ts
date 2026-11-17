/** Pure helpers of the API tokens dialog: name check, expiry choices, row labels and list updates. */
import { ApiTokenError } from './errors';
import type { ApiToken } from '@/types/apiTokens';

export const MAX_TOKEN_NAME_LENGTH = 64;

export type TokenNameCheck = { readonly ok: true; readonly name: string } | { readonly ok: false; readonly error: ApiTokenError };

/** The name as the server will store it (trimmed, 1..64 code points), or a local `invalid_token_name`. */
export function checkTokenName(raw: string): TokenNameCheck {
  const name = raw.trim();
  const length = Array.from(name).length;
  if (length === 0 || length > MAX_TOKEN_NAME_LENGTH) {
    return { ok: false, error: new ApiTokenError('invalid_token_name', `Name must be 1 to ${MAX_TOKEN_NAME_LENGTH} characters`) };
  }
  return { ok: true, name };
}

/** Days until expiry, or null for never. */
export type ExpiryDays = 30 | 90 | 365 | null;

export interface ExpiryChoice {
  readonly days: ExpiryDays;
  readonly label: string;
}

export const EXPIRY_CHOICES: readonly ExpiryChoice[] = [
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
  { days: 365, label: '1 year' },
  { days: null, label: 'Never' },
];

export const DEFAULT_EXPIRY: ExpiryDays = 90;

/** The `<option value>` of a choice. */
export function expiryValue(days: ExpiryDays): string {
  return days === null ? 'never' : String(days);
}

/** Back from an `<option value>`; anything unknown is the default. */
export function expiryFromValue(value: string): ExpiryDays {
  const choice = EXPIRY_CHOICES.find((c) => expiryValue(c.days) === value);
  return choice === undefined ? DEFAULT_EXPIRY : choice.days;
}

export interface TokenRowLabels {
  readonly prefix: string;
  readonly created: string;
  readonly lastUsed: string;
  readonly expires: string;
  readonly expired: boolean;
}

const defaultDate = (iso: string): string => new Date(iso).toLocaleDateString();

/** The display strings of one token row. `formatDate` is injectable for tests. */
export function formatTokenRow(token: ApiToken, now: Date, formatDate: (iso: string) => string = defaultDate): TokenRowLabels {
  const expired = token.expires_at !== null && Date.parse(token.expires_at) <= now.getTime();
  return {
    prefix: `${token.prefix}…`,
    created: formatDate(token.created_at),
    lastUsed: token.last_used_at === null ? 'Never used' : formatDate(token.last_used_at),
    expires: token.expires_at === null ? 'Never' : expired ? `Expired ${formatDate(token.expires_at)}` : formatDate(token.expires_at),
    expired,
  };
}

/** Newest first (like the server), id ascending on equal times. */
export function sortTokens(tokens: readonly ApiToken[]): ApiToken[] {
  return [...tokens].sort((a, b) => {
    const d = Date.parse(b.created_at) - Date.parse(a.created_at);
    if (d !== 0) return d;
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
}

/** Put a freshly created token on top (replacing an entry with the same id). */
export function addToken(tokens: readonly ApiToken[], token: ApiToken): ApiToken[] {
  return [token, ...tokens.filter((t) => t.id !== token.id)];
}

export function removeToken(tokens: readonly ApiToken[], id: string): ApiToken[] {
  return tokens.filter((t) => t.id !== id);
}
