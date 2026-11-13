import { useEffect, useMemo, useRef } from 'react';
import { useRhymeStore } from '@/stores/rhymeStore';
import { useCanvasStore } from '@/stores/canvasStore';
import { useRhymePatternStore } from '@/stores/rhymePatternStore';
import { createDebouncedRunner } from '@/lib/rhyme/debounce';

export const RHYME_QUERY_DEBOUNCE_MS = 150;

/**
 * Re-runs the rhyme query while the sidebar is open, after a 150 ms quiet period on selection,
 * typed anchor, chip, anchor word range, tag filter, or parameter changes. The first query is run by `rhymeStore.enter()` itself.
 * Paused while the sidebar is in Pattern mode (useRhymePatternQuery owns the queries then); leaving it re-queries.
 */
export function useRhymeQuery(): void {
  const active = useRhymeStore((s) => s.active);
  const typedAnchor = useRhymeStore((s) => s.typedAnchor);
  const excludedAnchorIds = useRhymeStore((s) => s.excludedAnchorIds);
  const anchorRanges = useRhymeStore((s) => s.anchorRanges);
  const k = useRhymeStore((s) => s.k);
  const minScore = useRhymeStore((s) => s.minScore);
  const slotMode = useRhymeStore((s) => s.slotMode);
  const slot = useRhymeStore((s) => s.slot);
  const sources = useRhymeStore((s) => s.sources);
  const includeRare = useRhymeStore((s) => s.includeRare);
  const tagFilterText = useRhymeStore((s) => s.tagFilterText);
  const selectedSnippetIds = useCanvasStore((s) => s.selectedSnippetIds);
  const patternOn = useRhymePatternStore((s) => s.view.kind !== 'off');
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
    if (patternOn) {
      runner.cancel();
      return;
    }
    runner.schedule();
  }, [patternOn, active, typedAnchor, excludedAnchorIds, anchorRanges, k, minScore, slotMode, manualSlot, sources, includeRare, tagFilterText, selectedSnippetIds, runner]);

  useEffect(() => () => runner.cancel(), [runner]);
}
