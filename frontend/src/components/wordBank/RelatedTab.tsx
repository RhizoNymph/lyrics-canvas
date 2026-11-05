import { useEffect, useState } from 'react';
import { useWordBankQueryStore } from '@/stores/wordBankQueryStore';
import RelatedView from './RelatedView';

/** The related-word search; leaving the tab stops any warming retries. */
export default function RelatedTab() {
  const state = useWordBankQueryStore((s) => s.related);
  const search = useWordBankQueryStore((s) => s.searchRelated);
  const cancel = useWordBankQueryStore((s) => s.cancelRelated);
  const add = useWordBankQueryStore((s) => s.addRelatedWord);
  const [query, setQuery] = useState(state.kind === 'idle' ? '' : state.q);

  useEffect(() => () => {
    const current = useWordBankQueryStore.getState().related;
    if (current.kind === 'warming' || current.kind === 'loading') cancel();
  }, [cancel]);

  return <RelatedView query={query} state={state} onQuery={setQuery} onSearch={() => void search(query)} onAdd={(text) => void add(text)} />;
}
