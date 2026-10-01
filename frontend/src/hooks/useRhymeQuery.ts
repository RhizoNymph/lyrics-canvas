import { useEffect, useMemo, useRef } from 'react';
import { useRhymeStore } from '@/stores/rhymeStore';
import { useCanvasStore } from '@/stores/canvasStore';
import { createDebouncedRunner } from '@/lib/rhyme/debounce';

export const RHYME_QUERY_DEBOUNCE_MS = 150;

/**
 * Re-runs the rhyme query while the sidebar is open, after a 150 ms quiet period on selection,
 * typed anchor, chip, or parameter changes. The first query is run by `rhymeStore.enter()` itself.
 */
export function useRhymeQuery(): void {
  const active = useRhymeStore((s) => s.active);
  const typedAnchor = useRhymeStore((s) => s.typedAnchor);
  const excludedAnchorIds = useRhymeStore((s) => s.excludedAnchorIds);
  const k = useRhymeStore((s) => s.k);
  const minScore = useRhymeStore((s) => s.minScore);
  const slotMode = useRhymeStore((s) => s.slotMode);
  const slot = useRhymeStore((s) => s.slot);
  const sources = useRhymeStore((s) => s.sources);
  const includeRare = useRhymeStore((s) => s.includeRare);
  const selectedSnippetIds = useCanvasStore((s) => s.selectedSnippetIds);
  // In from-selection mode the store rewrites `slot` after each query; only manual edits should re-query.
  const manualSlot = slotMode === 'manual' ? slot : null;

  const runner = useMemo(
    () => createDebouncedRunner(() => void useRhymeStore.getState().runQuery(), RHYME_QUERY_DEBOUNCE_MS),
    []
  );
  const wasActive = useRef(false);

  useEffect(() => {
    if (!active) {
      runner.cancel();
      wasActive.current = false;
      return;
    }
    if (!wasActive.current) {
      // enter() already queried; only later changes schedule a re-query.
      wasActive.current = true;
      return;
    }
    runner.schedule();
  }, [active, typedAnchor, excludedAnchorIds, k, minScore, slotMode, manualSlot, sources, includeRare, selectedSnippetIds, runner]);

  useEffect(() => () => runner.cancel(), [runner]);
}
