import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { DEFAULT_EXPIRY, copyToClipboard } from '@/lib/apiTokens';
import type { ExpiryDays } from '@/lib/apiTokens';
import { useApiTokenStore } from '@/stores/apiTokenStore';
import { useFocusTrap } from '@/components/voice/useFocusTrap';
import ApiTokensView from './ApiTokensView';
import type { CopyState } from './ApiTokensView';

/**
 * The account menu's "API tokens" dialog: loads the list on open; closing forgets the one-time secret
 * (`reset`). Escape cancels a pending revoke confirmation first, then closes.
 */
export default function ApiTokensDialog({ onClose }: { onClose: () => void }) {
  const store = useApiTokenStore();
  const [name, setName] = useState('');
  const [expiry, setExpiry] = useState<ExpiryDays>(DEFAULT_EXPIRY);
  const [copyState, setCopyState] = useState<CopyState>('idle');
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const surfaceRef = useRef<HTMLDivElement>(null);
  const onTrapKeyDown = useFocusTrap(surfaceRef);

  useEffect(() => {
    void useApiTokenStore.getState().load();
    return () => useApiTokenStore.getState().reset();
  }, []);

  const close = () => {
    store.reset();
    onClose();
  };

  const onKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Tab') onTrapKeyDown(e);
    else if (e.key === 'Escape') {
      e.preventDefault();
      if (confirmingId !== null) setConfirmingId(null);
      else close();
    }
  };

  return (
    <ApiTokensView
      tokens={store.tokens}
      loadState={store.loadState}
      loadError={store.loadError}
      now={new Date()}
      name={name}
      expiry={expiry}
      createState={store.createState}
      revealed={store.revealed}
      copyState={copyState}
      confirmingId={confirmingId}
      revokingId={store.revokingId}
      revokeError={store.revokeError}
      surfaceRef={surfaceRef}
      onNameChange={(v) => {
        setName(v);
        store.clearCreateError();
      }}
      onExpiryChange={setExpiry}
      onCreate={async () => {
        const result = await store.create(name, expiry);
        if (result.ok) {
          setName('');
          setExpiry(DEFAULT_EXPIRY);
          setCopyState('idle');
        }
      }}
      onCopy={async () => {
        const secret = useApiTokenStore.getState().revealed?.secret;
        if (secret === undefined) return;
        setCopyState(await copyToClipboard(secret, navigator.clipboard));
      }}
      onDismissSecret={() => {
        store.dismissSecret();
        setCopyState('idle');
      }}
      onAskRevoke={setConfirmingId}
      onCancelRevoke={() => setConfirmingId(null)}
      onConfirmRevoke={async (id) => {
        const result = await store.revoke(id);
        if (result.ok) setConfirmingId(null);
      }}
      onRetryLoad={() => void store.load()}
      onClose={close}
      onKeyDown={onKeyDown}
    />
  );
}
