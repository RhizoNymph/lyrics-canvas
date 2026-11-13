import { useEffect, useMemo, useRef } from 'react';
import { useRhymePatternStore } from '@/stores/rhymePatternStore';
import { useRhymeStore } from '@/stores/rhymeStore';
import { createDebouncedRunner } from '@/lib/rhyme/debounce';
import { RHYME_QUERY_DEBOUNCE_MS } from './useRhymeQuery';

/**
 * Re-runs the pattern search 150 ms after a slot edit or a change of the shared rhyme controls (min score,
 * sources, rare words, tag filter), and re-detects 150 ms after the detection threshold changes. The first
 * search of a detection is run by the store itself. Mounted by the pattern panel.
 */
export function useRhymePatternQuery(): void {
  const edit = useRhymePatternStore((s) => (s.view.kind === 'ready' ? s.view.edit : null));
  const detectMinScore = useRhymePatternStore((s) => s.detectMinScore);
  const minScore = useRhymeStore((s) => s.minScore);
  const sources = useRhymeStore((s) => s.sources);
  const includeRare = useRhymeStore((s) => s.includeRare);
  const tagFilterText = useRhymeStore((s) => s.tagFilterText);

  const suggestRunner = useMemo(
    () => createDebouncedRunner(() => void useRhymePatternStore.getState().runSuggest(), RHYME_QUERY_DEBOUNCE_MS),
    [],
  );
  const detectRunner = useMemo(
    () => createDebouncedRunner(() => useRhymePatternStore.getState().reset(), RHYME_QUERY_DEBOUNCE_MS),
    [],
  );
  const hadEdit = useRef(false);
  const firstDetect = useRef(true);

  useEffect(() => {
    if (edit === null) {
      // Detecting (or off): the store searches as soon as the new detection lands.
      suggestRunner.cancel();
      hadEdit.current = false;
      return;
    }
    if (!hadEdit.current) {
      hadEdit.current = true;
      return;
    }
    suggestRunner.schedule();
  }, [edit, minScore, sources, includeRare, tagFilterText, suggestRunner]);

  useEffect(() => {
    if (firstDetect.current) {
      firstDetect.current = false;
      return;
    }
    detectRunner.schedule();
  }, [detectMinScore, detectRunner]);

  useEffect(
    () => () => {
      suggestRunner.cancel();
      detectRunner.cancel();
    },
    [suggestRunner, detectRunner],
  );
}
