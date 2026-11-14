import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, RefObject } from 'react';
import { EXPIRY_CHOICES, MAX_TOKEN_NAME_LENGTH, describeApiTokenError, expiryFromValue, expiryValue, formatTokenRow } from '@/lib/apiTokens';
import type { ApiTokenError, ExpiryDays } from '@/lib/apiTokens';
import type { ApiTokenLoadState, CreateState } from '@/stores/apiTokenStore';
import type { ApiToken, ApiTokenCreateResponse } from '@/types/apiTokens';

export type CopyState = 'idle' | 'copied' | 'failed';

export interface ApiTokensViewProps {
  tokens: readonly ApiToken[];
  loadState: ApiTokenLoadState;
  loadError: ApiTokenError | null;
  now: Date;
  formatDate?: (iso: string) => string;
  name: string;
  expiry: ExpiryDays;
  createState: CreateState;
  revealed: ApiTokenCreateResponse | null;
  copyState: CopyState;
  /** The row whose revoke is awaiting confirmation. */
  confirmingId: string | null;
  revokingId: string | null;
  revokeError: { readonly id: string; readonly error: ApiTokenError } | null;
  surfaceRef?: RefObject<HTMLDivElement>;
  onNameChange: (name: string) => void;
  onExpiryChange: (days: ExpiryDays) => void;
  onCreate: () => void;
  onCopy: () => void;
  onDismissSecret: () => void;
  onAskRevoke: (id: string) => void;
  onCancelRevoke: () => void;
  onConfirmRevoke: (id: string) => void;
  onRetryLoad: () => void;
  onClose: () => void;
  onKeyDown?: (e: ReactKeyboardEvent<HTMLDivElement>) => void;
}

const TITLE_ID = 'api-tokens-title';

const button: CSSProperties = {
  border: '1px solid #3a3a5a',
  borderRadius: 6,
  padding: '6px 12px',
  fontSize: 12,
  cursor: 'pointer',
  background: '#2a2a4a',
  color: '#e0e0e0',
};
const primary: CSSProperties = { ...button, background: '#6366f1', borderColor: '#6366f1', color: '#fff' };
const danger: CSSProperties = { ...button, background: '#7f1d1d', borderColor: '#991b1b', color: '#fff' };
const field: CSSProperties = {
  background: '#1e1e3a',
  color: '#e0e0e0',
  border: '1px solid #3a3a5a',
  borderRadius: 6,
  padding: '7px 10px',
  fontSize: 13,
};
const muted: CSSProperties = { fontSize: 11, color: '#8a8aaa' };

function SecretPanel(props: Pick<ApiTokensViewProps, 'revealed' | 'copyState' | 'onCopy' | 'onDismissSecret'>) {
  if (props.revealed === null) return null;
  const copyLabel = props.copyState === 'copied' ? 'Copied' : props.copyState === 'failed' ? 'Copy failed' : 'Copy';
  return (
    <div
      data-token-secret
      style={{ border: '1px solid #b45309', background: '#2a1f0f', borderRadius: 8, padding: 12, display: 'flex', flexDirection: 'column', gap: 8 }}
    >
      <div style={{ fontSize: 13, fontWeight: 600 }}>{`Token "${props.revealed.token.name}" created`}</div>
      <div role="note" style={{ fontSize: 12, color: '#fbbf24', lineHeight: 1.5 }}>
        Copy it now: it will not be shown again. Anyone with this token can read and change your lyrics, so keep it
        private.
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <code
          aria-label="New token"
          style={{ flex: 1, minWidth: 0, overflowWrap: 'anywhere', userSelect: 'all', fontSize: 12, background: '#12122a', padding: '6px 8px', borderRadius: 4 }}
        >
          {props.revealed.secret}
        </code>
        <button type="button" style={primary} onClick={props.onCopy}>
          {copyLabel}
        </button>
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <button type="button" style={button} onClick={props.onDismissSecret}>
          Done
        </button>
      </div>
    </div>
  );
}

function CreateForm(props: ApiTokensViewProps) {
  const saving = props.createState.kind === 'saving';
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        props.onCreate();
      }}
      style={{ display: 'flex', flexDirection: 'column', gap: 6 }}
    >
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <input
          value={props.name}
          onChange={(e) => props.onNameChange(e.target.value)}
          placeholder="Token name, e.g. laptop agent"
          aria-label="Token name"
          maxLength={MAX_TOKEN_NAME_LENGTH}
          disabled={saving}
          spellCheck={false}
          style={{ ...field, flex: 1, minWidth: 160 }}
        />
        <select
          value={expiryValue(props.expiry)}
          onChange={(e) => props.onExpiryChange(expiryFromValue(e.target.value))}
          aria-label="Expires after"
          disabled={saving}
          style={field}
        >
          {EXPIRY_CHOICES.map((c) => (
            <option key={expiryValue(c.days)} value={expiryValue(c.days)}>
              {c.label}
            </option>
          ))}
        </select>
        <button type="submit" style={primary} disabled={saving}>
          {saving ? 'Creating…' : 'Create token'}
        </button>
      </div>
      {props.createState.kind === 'failed' && (
        <div role="alert" style={{ fontSize: 12, color: '#f87171' }}>
          {describeApiTokenError(props.createState.error)}
        </div>
      )}
    </form>
  );
}

function TokenRow({ token, props }: { token: ApiToken; props: ApiTokensViewProps }) {
  const labels = formatTokenRow(token, props.now, props.formatDate);
  const confirming = props.confirmingId === token.id;
  const revoking = props.revokingId === token.id;
  const error = props.revokeError?.id === token.id ? props.revokeError.error : null;
  return (
    <li style={{ padding: '10px 0', borderTop: '1px solid #2a2a4a', display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 13, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{token.name}</div>
          <div style={muted}>
            <code>{labels.prefix}</code>
            {` · Created ${labels.created} · Last used: ${labels.lastUsed} · `}
            <span style={labels.expired ? { color: '#f87171' } : undefined}>{`Expires: ${labels.expires}`}</span>
          </div>
        </div>
        {!confirming && (
          <button type="button" style={button} aria-label={`Revoke ${token.name}`} onClick={() => props.onAskRevoke(token.id)}>
            Revoke
          </button>
        )}
      </div>
      {confirming && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
          <span style={{ flex: 1, color: '#fca5a5' }}>{`Revoke ${token.name}? Anything using it stops working.`}</span>
          <button type="button" style={button} disabled={revoking} onClick={props.onCancelRevoke}>
            Cancel
          </button>
          <button type="button" style={danger} disabled={revoking} onClick={() => props.onConfirmRevoke(token.id)}>
            {revoking ? 'Revoking…' : 'Revoke'}
          </button>
        </div>
      )}
      {error !== null && (
        <div role="alert" style={{ fontSize: 12, color: '#f87171' }}>
          {describeApiTokenError(error)}
        </div>
      )}
    </li>
  );
}

/** The "API tokens" dialog of the account menu: token list with revoke, create form, one-time secret. */
export default function ApiTokensView(props: ApiTokensViewProps) {
  return (
    <div
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) props.onClose();
      }}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 3000,
        background: 'rgba(10, 10, 28, 0.8)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
      }}
    >
      <div
        ref={props.surfaceRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={TITLE_ID}
        tabIndex={-1}
        onKeyDown={props.onKeyDown}
        style={{
          width: 'min(560px, 100%)',
          maxHeight: 'calc(100vh - 32px)',
          overflowY: 'auto',
          background: '#1c1c38',
          border: '1px solid #3a3a5a',
          borderRadius: 10,
          padding: 20,
          boxSizing: 'border-box',
          color: '#e0e0e0',
          display: 'flex',
          flexDirection: 'column',
          gap: 14,
          outline: 'none',
        }}
      >
        <h2 id={TITLE_ID} style={{ margin: 0, fontSize: 16, fontWeight: 600 }}>
          API tokens
        </h2>
        <p style={{ margin: 0, fontSize: 12, color: '#8a8aaa', lineHeight: 1.5 }}>
          Personal tokens let scripts and agents use your account through the API. Revoke a token to cut it off at once.
        </p>

        {props.revealed !== null ? <SecretPanel {...props} /> : <CreateForm {...props} />}

        {props.loadState === 'loading' && props.tokens.length === 0 && <div style={{ fontSize: 13, color: '#a0a0c0' }}>Loading…</div>}
        {props.loadState === 'failed' && props.loadError !== null && (
          <div role="alert" style={{ fontSize: 13, color: '#f87171', display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ flex: 1 }}>{`Tokens unavailable: ${describeApiTokenError(props.loadError)}`}</span>
            <button type="button" style={button} onClick={props.onRetryLoad}>
              Retry
            </button>
          </div>
        )}
        {props.loadState === 'ready' && props.tokens.length === 0 && (
          <div style={{ fontSize: 13, color: '#8a8aaa' }}>No tokens yet.</div>
        )}
        {props.tokens.length > 0 && (
          <ul aria-label="Your API tokens" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {props.tokens.map((t) => (
              <TokenRow key={t.id} token={t} props={props} />
            ))}
          </ul>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <button type="button" style={button} onClick={props.onClose}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
