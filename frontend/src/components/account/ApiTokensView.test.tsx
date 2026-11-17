import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import ApiTokensView from './ApiTokensView';
import type { ApiTokensViewProps } from './ApiTokensView';
import { ApiTokenError } from '@/lib/apiTokens';
import type { ApiToken } from '@/types/apiTokens';

const noop = () => undefined;
const tok = (id: string, extra: Partial<ApiToken> = {}): ApiToken => ({
  id, name: `name-${id}`, prefix: 'lct_abcdefgh', created_at: '2026-11-01T00:00:00Z', last_used_at: null, expires_at: null, ...extra,
});
const SECRET = 'lct_' + 'q'.repeat(43);

function render(over: Partial<ApiTokensViewProps> = {}) {
  return renderToStaticMarkup(
    <ApiTokensView
      tokens={[tok('a')]}
      loadState="ready"
      loadError={null}
      now={new Date('2026-11-14T00:00:00Z')}
      formatDate={(iso) => `D(${iso.slice(0, 10)})`}
      name=""
      expiry={90}
      createState={{ kind: 'idle' }}
      revealed={null}
      copyState="idle"
      confirmingId={null}
      revokingId={null}
      revokeError={null}
      onNameChange={noop}
      onExpiryChange={noop}
      onCreate={noop}
      onCopy={noop}
      onDismissSecret={noop}
      onAskRevoke={noop}
      onCancelRevoke={noop}
      onConfirmRevoke={noop}
      onRetryLoad={noop}
      onClose={noop}
      {...over}
    />,
  );
}

describe('ApiTokensView', () => {
  it('is a labelled modal dialog', () => {
    const html = render();
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('API tokens');
  });

  it('lists name, prefix, created, last used and expiry', () => {
    const html = render({
      tokens: [
        tok('a'),
        tok('b', { last_used_at: '2026-11-10T00:00:00Z', expires_at: '2027-01-01T00:00:00Z' }),
        tok('c', { expires_at: '2026-11-01T00:00:00Z' }),
      ],
    });
    expect(html).toContain('name-a');
    expect(html).toContain('lct_abcdefgh…');
    expect(html).toContain('D(2026-11-01)');
    expect(html).toContain('Never used');
    expect(html).toContain('D(2026-11-10)');
    expect(html).toContain('D(2027-01-01)');
    expect(html).toContain('Expired D(2026-11-01)');
    expect(html).toContain('Never');
  });

  it('shows loading, empty and failed lists', () => {
    expect(render({ loadState: 'loading', tokens: [] })).toContain('Loading');
    expect(render({ tokens: [] })).toContain('No tokens yet');
    const failed = render({ loadState: 'failed', tokens: [], loadError: new ApiTokenError('token_management_forbidden', '', 403) });
    expect(failed).toContain('role="alert"');
    expect(failed).toContain('signed-in session');
    expect(failed).toContain('Retry');
  });

  it('has the create form with the four expiry choices, 90 days selected', () => {
    const html = render();
    expect(html).toContain('Token name');
    expect(html).toMatch(/maxLength="64"/);
    for (const label of ['30 days', '90 days', '1 year', 'Never']) expect(html).toContain(`>${label}</option>`);
    expect(html).toMatch(/<option value="90" selected="">90 days<\/option>/);
    expect(render({ expiry: null })).toMatch(/<option value="never" selected="">Never<\/option>/);
    expect(html).toContain('Create token');
  });

  it('disables create while saving and shows a create error', () => {
    expect(render({ createState: { kind: 'saving' } })).toMatch(/disabled=""[^>]*>Creating…/);
    const failed = render({ createState: { kind: 'failed', error: new ApiTokenError('invalid_token_name', '') } });
    expect(failed).toContain('Name the token (1 to 64 characters)');
  });

  it('reveals the secret once with a copy button and a warning', () => {
    const html = render({ revealed: { token: tok('a'), secret: SECRET } });
    expect(html).toContain(SECRET);
    expect(html).toContain('Copy');
    expect(html).toMatch(/will not be shown again/);
    expect(html).toContain('Done');
    expect(render({ revealed: { token: tok('a'), secret: SECRET }, copyState: 'copied' })).toContain('Copied');
    expect(render({ revealed: { token: tok('a'), secret: SECRET }, copyState: 'failed' })).toContain('Copy failed');
    expect(render()).not.toContain('will not be shown again');
  });

  it('hides the create form while a secret is shown', () => {
    expect(render({ revealed: { token: tok('a'), secret: SECRET } })).not.toContain('Create token');
  });

  it('asks before revoking', () => {
    expect(render()).toContain('aria-label="Revoke name-a"');
    const confirming = render({ confirmingId: 'a' });
    expect(confirming).toContain('Revoke name-a? Anything using it stops working.');
    expect(confirming).toContain('Cancel');
    expect(confirming).toContain('>Revoke</button>');
    expect(render({ confirmingId: 'a', revokingId: 'a' })).toMatch(/disabled=""[^>]*>Revoking…/);
  });

  it('shows a revoke error on its row', () => {
    const html = render({ revokeError: { id: 'a', error: new ApiTokenError('network', '') } });
    expect(html).toContain('Could not reach the server');
  });
});
