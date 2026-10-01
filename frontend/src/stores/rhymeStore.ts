import { create } from 'zustand';
import { nlpApi } from '@/api/client';
import { useCanvasStore } from './canvasStore';
import { anchorsFromSelection, anchorSnippets } from '@/lib/rhyme/queryFromSelection';
import { slotFromRange, slotFromSnippet } from '@/lib/rhyme/slotFit';
import { rangeFits } from '@/lib/rhyme/anchorRange';
import { materializePosition } from '@/lib/rhyme/materializePosition';
import type { ViewSize } from '@/lib/rhyme/geometry';
import type { Snippet } from '@/types';
import { toRhymeError } from '@/lib/rhyme/errors';
import { RHYME_PAGE_SIZE } from '@/types/rhyme';
import type {
  AnchorRange,
  RhymeK,
  RhymeUnit,
  RhymeCandidate,
  RhymeSuggestRequest,
  RhymeError,
  Slot,
  SourceName,
  DerivedFrom,
} from '@/types/rhyme';

export type SlotMode = 'manual' | 'from-selection';

/**
 * Single owner of the rhyme sidebar session. Nothing here touches the canvas until
 * `materialize` creates a real snippet through canvasStore.
 */
export interface RhymeState {
  /** True while the rhyme sidebar is open. */
  active: boolean;
  typedAnchor: string;
  /** Selected snippet ids removed from the anchors via their chip; pruned to the selection on each query. */
  excludedAnchorIds: Set<string>;
  /** Word ranges picked on a chip, by snippet id; a snippet without one anchors on its whole last line. Pruned like exclusions. */
  anchorRanges: Map<string, AnchorRange>;
  k: RhymeK;
  minScore: number;
  slot: Slot | null;
  slotMode: SlotMode;
  sources: { notes: boolean; dictionary: boolean };
  includeRare: boolean;
  anchors: RhymeUnit[];
  candidates: RhymeCandidate[];
  total: number;
  offset: number;
  dismissed: Set<string>;
  /** Materializations made per anchor snippet id this session (drives the row step-down). */
  materializeCounts: Map<string, number>;
  /** Canvas element size, for the viewport-centre fallback when materializing without a single anchor. */
  viewSize: ViewSize;
  loading: boolean;
  error: RhymeError | null;

  /** Opens the session and queries immediately. */
  enter: () => void;
  exit: () => void;
  setTypedAnchor: (t: string) => void;
  excludeAnchor: (snippetId: string) => void;
  restoreAnchor: (snippetId: string) => void;
  /** Anchor the snippet on a word range of one of its lines; null restores the whole line. */
  setAnchorRange: (snippetId: string, range: AnchorRange | null) => void;
  setK: (k: RhymeK) => void;
  setMinScore: (v: number) => void;
  setSlot: (s: Slot | null) => void;
  setSlotMode: (m: SlotMode) => void;
  toggleSource: (name: SourceName) => void;
  setIncludeRare: (v: boolean) => void;
  setViewSize: (width: number, height: number) => void;
  /** Debounced 150 ms by useRhymeQuery; replaces the candidate set (offset 0). */
  runQuery: () => Promise<void>;
  /** Appends the next page. */
  loadMore: () => Promise<void>;
  dismiss: (id: string) => void;
  /** Creates a snippet from the candidate on the canvas, then removes the row. */
  materialize: (id: string) => Promise<void>;
}

function cleared() {
  return {
    typedAnchor: '',
    excludedAnchorIds: new Set<string>(),
    anchorRanges: new Map<string, AnchorRange>(),
    anchors: [] as RhymeUnit[],
    candidates: [] as RhymeCandidate[],
    total: 0,
    offset: 0,
    dismissed: new Set<string>(),
    materializeCounts: new Map<string, number>(),
    loading: false,
    error: null,
  };
}

/** Slot "from selection": the first anchor snippet's picked word range, else its whole last line. */
function slotFromFirstAnchor(anchorSnips: Snippet[], ranges: ReadonlyMap<string, AnchorRange>): Slot | null {
  if (anchorSnips.length === 0) return null;
  const first = anchorSnips[0];
  const range = ranges.get(first.id);
  return range === undefined ? slotFromSnippet(first, null) : slotFromRange(first, range);
}

/** Monotonic query counter so a slow, older response never overwrites a newer one. */
let querySeq = 0;

function enabledSources(sources: RhymeState['sources']): SourceName[] {
  const out: SourceName[] = [];
  if (sources.notes) out.push('notes');
  if (sources.dictionary) out.push('dictionary');
  return out;
}

function derivedFromFor(c: RhymeCandidate, anchors: RhymeUnit[], k: RhymeK): DerivedFrom {
  const anchor = anchors.find((a) => a.id === c.best_anchor_id);
  return {
    provenance: c.provenance,
    source_unit_id: c.id,
    score: c.score,
    match_depth: c.match_depth,
    k,
    anchor_unit_id: c.best_anchor_id,
    anchor_text: anchor?.text ?? '',
  };
}

function sameSet(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
  if (a.size !== b.size) return false;
  for (const x of a) if (!b.has(x)) return false;
  return true;
}

function sameRanges(a: ReadonlyMap<string, AnchorRange>, b: ReadonlyMap<string, AnchorRange>): boolean {
  if (a.size !== b.size) return false;
  for (const [id, r] of a) if (b.get(id) !== r) return false;
  return true;
}

export const useRhymeStore = create<RhymeState>((set, get) => {
  /** Drop exclusions for snippets no longer selected ("for this query" semantics). */
  const pruneExclusions = (): Set<string> => {
    const { excludedAnchorIds } = get();
    const { selectedSnippetIds } = useCanvasStore.getState();
    const pruned = new Set(Array.from(excludedAnchorIds).filter((id) => selectedSnippetIds.has(id)));
    if (!sameSet(pruned, excludedAnchorIds)) set({ excludedAnchorIds: pruned });
    return pruned;
  };

  /** Drop ranges for snippets no longer selected, and ranges the snippet's current text can no longer satisfy. */
  const pruneRanges = (): Map<string, AnchorRange> => {
    const { anchorRanges } = get();
    const { selectedSnippetIds, snippets } = useCanvasStore.getState();
    const pruned = new Map<string, AnchorRange>();
    for (const [id, range] of anchorRanges) {
      const snippet = snippets.get(id);
      if (selectedSnippetIds.has(id) && snippet !== undefined && rangeFits(snippet, range)) pruned.set(id, range);
    }
    if (!sameRanges(pruned, anchorRanges)) set({ anchorRanges: pruned });
    return pruned;
  };

  /** Build the request for the current state, or null when nothing should be queried. */
  const buildRequest = (offset: number): { request: RhymeSuggestRequest; slot: Slot | null } | null => {
    const state = get();
    const canvas = useCanvasStore.getState();
    if (!state.active || !canvas.canvasId) return null;
    const excluded = pruneExclusions();
    const ranges = pruneRanges();
    const anchors = anchorsFromSelection(canvas.selectedSnippetIds, canvas.snippets, state.typedAnchor, excluded, ranges);
    const sources = enabledSources(state.sources);
    if (anchors.length === 0 || sources.length === 0) return null;

    const anchorSnips = anchorSnippets(canvas.selectedSnippetIds, canvas.snippets, excluded);
    const slot = state.slotMode === 'from-selection' ? slotFromFirstAnchor(anchorSnips, ranges) : state.slot;
    return {
      request: {
        canvas_id: canvas.canvasId,
        anchors,
        context: [],
        slot,
        k: state.k,
        min_score: state.minScore,
        sources,
        include_rare: state.includeRare,
        exclude_ids: Array.from(state.dismissed),
        limit: RHYME_PAGE_SIZE,
        offset,
        weights: null,
        dialect: null,
      },
      slot,
    };
  };

  return {
    active: false,
    k: 2,
    minScore: 0.5,
    slot: null,
    slotMode: 'from-selection',
    sources: { notes: true, dictionary: true },
    includeRare: false,
    viewSize: { width: 800, height: 600 },
    ...cleared(),

    enter: () => {
      set({ active: true, error: null });
      void get().runQuery();
    },

    exit: () => {
      querySeq++;
      set({ active: false, ...cleared() });
    },

    setTypedAnchor: (t) => set({ typedAnchor: t }),
    excludeAnchor: (snippetId) =>
      set((s) => {
        const next = new Set(s.excludedAnchorIds);
        next.add(snippetId);
        return { excludedAnchorIds: next };
      }),
    restoreAnchor: (snippetId) =>
      set((s) => {
        const next = new Set(s.excludedAnchorIds);
        next.delete(snippetId);
        return { excludedAnchorIds: next };
      }),
    setAnchorRange: (snippetId, range) =>
      set((s) => {
        const next = new Map(s.anchorRanges);
        if (range === null) next.delete(snippetId);
        else next.set(snippetId, range);
        return { anchorRanges: next };
      }),
    setK: (k) => set({ k }),
    setMinScore: (v) => set({ minScore: Math.max(0, Math.min(1, v)) }),
    setSlot: (s) => set({ slot: s }),
    setSlotMode: (m) => set({ slotMode: m }),
    toggleSource: (name) => set((s) => ({ sources: { ...s.sources, [name]: !s.sources[name] } })),
    setIncludeRare: (v) => set({ includeRare: v }),
    setViewSize: (width, height) => set({ viewSize: { width, height } }),

    runQuery: async () => {
      const built = buildRequest(0);
      const seq = ++querySeq;
      if (!built) {
        if (get().active) {
          set({ anchors: [], candidates: [], total: 0, offset: 0, loading: false, error: null });
        }
        return;
      }
      set({ loading: true, error: null, slot: built.slot });
      try {
        const res = await nlpApi.rhymeSuggest(built.request);
        if (seq !== querySeq) return;
        const { dismissed } = get();
        set({
          anchors: res.data.anchors,
          candidates: res.data.candidates.filter((c) => !dismissed.has(c.id)),
          total: res.data.total,
          offset: 0,
          loading: false,
          error: null,
        });
      } catch (err) {
        if (seq !== querySeq) return;
        set({ loading: false, error: toRhymeError(err, 'query') });
      }
    },

    loadMore: async () => {
      const state = get();
      const nextOffset = state.offset + RHYME_PAGE_SIZE;
      if (state.loading || nextOffset >= state.total) return;
      const built = buildRequest(nextOffset);
      if (!built) return;
      const seq = ++querySeq;
      set({ loading: true, error: null });
      try {
        const res = await nlpApi.rhymeSuggest(built.request);
        if (seq !== querySeq) return;
        const { candidates, dismissed } = get();
        const seen = new Set(candidates.map((c) => c.id));
        const fresh = res.data.candidates.filter((c) => !dismissed.has(c.id) && !seen.has(c.id));
        set({
          candidates: [...candidates, ...fresh],
          total: res.data.total,
          offset: nextOffset,
          loading: false,
          error: null,
        });
      } catch (err) {
        if (seq !== querySeq) return;
        set({ loading: false, error: toRhymeError(err, 'query') });
      }
    },

    dismiss: (id) => {
      const { candidates, dismissed } = get();
      const next = new Set(dismissed);
      next.add(id);
      set({ candidates: candidates.filter((c) => c.id !== id), dismissed: next });
    },

    materialize: async (id) => {
      const { candidates, anchors, k, excludedAnchorIds, materializeCounts, viewSize } = get();
      const candidate = candidates.find((c) => c.id === id);
      if (!candidate) return;
      const canvas = useCanvasStore.getState();
      const anchorSnips = anchorSnippets(canvas.selectedSnippetIds, canvas.snippets, excludedAnchorIds);
      const at = materializePosition({ anchors: anchorSnips, counts: materializeCounts, viewport: canvas.viewport, viewSize });
      try {
        const created = await canvas.addSnippetFromText(candidate.text, at.x, at.y, derivedFromFor(candidate, anchors, k));
        if (created === null) return;
        if (anchorSnips.length === 1) {
          const counts = new Map(get().materializeCounts);
          const anchorId = anchorSnips[0].id;
          counts.set(anchorId, (counts.get(anchorId) ?? 0) + 1);
          set({ materializeCounts: counts });
        }
        get().dismiss(id);
        set({ error: null });
      } catch (err) {
        set({ error: toRhymeError(err, 'materialize') });
      }
    },
  };
});
