import { useEffect, useMemo, useState } from 'react';
import { searchApi } from '@/api/client';
import { createGlobalSearch } from '@/lib/search';
import type { GlobalSearchView } from '@/lib/search';

/** The canvas list page's search across canvases: the input text and what to show (see lib/search/globalSearch.ts). */
export function useGlobalSearch(): { input: string; setInput: (raw: string) => void; view: GlobalSearchView } {
  const [input, setInputState] = useState('');
  const [view, setView] = useState<GlobalSearchView>({ kind: 'off' });
  const controller = useMemo(
    () => createGlobalSearch({ search: (q, signal) => searchApi.searchAll(q, { signal }), onView: setView }),
    [],
  );
  useEffect(() => () => controller.dispose(), [controller]);
  const setInput = (raw: string) => {
    setInputState(raw);
    controller.setInput(raw);
  };
  return { input, setInput, view };
}
