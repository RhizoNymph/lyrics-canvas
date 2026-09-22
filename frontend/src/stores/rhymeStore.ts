import { create } from 'zustand';
import { nlpApi } from '@/api/client';
import { useCanvasStore } from './canvasStore';
import { anchorsFromSelection, selectedSnippets } from '@/lib/rhyme/queryFromSelection';
import { slotFromSnippet } from '@/lib/rhyme/slotFit';
import { layoutCandidates, anchorCenter } from '@/lib/rhyme/radialLayout';
import type { Point } from '@/lib/rhyme/radialLayout';
import { candidateCardSize, candidateTopLeft } from '@/lib/rhyme/candidateGeometry';
import { toRhymeError } from '@/lib/rhyme/errors';
import { RHYME_PAGE_SIZE } from '@/types/rhyme';
import type {
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

export interface RhymeState {
  active: boolean;
  typedAnchor: string;
  k: RhymeK;
  minScore: number;
  slot: Slot | null;
  slotMode: SlotMode;
  sources: { notes: boolean; dictionary: boolean };
  anchors: RhymeUnit[];
  candidates: RhymeCandidate[];
  total: number;
  offset: number;
  /** World-coordinate card centres keyed by unit id; stable across re-queries. */
  positions: Map<string, Point>;
  dismissed: Set<string>;
  hoveredId: string | null;
  /** Tooltip pinned by click; survives hover-out. */
  pinnedId: string | null;
  /** Canvas element size, used to centre typed-anchor layouts on the viewport. */
  viewSize: { width: number; height: number };
  loading: boolean;
  error: RhymeError | null;

  enter: () => void;
  exit: () => void;
  setTypedAnchor: (t: string) => void;
  setK: (k: RhymeK) => void;
  setMinScore: (v: number) => void;
  setSlot: (s: Slot | null) => void;
  setSlotMode: (m: SlotMode) => void;
  toggleSource: (name: SourceName) => void;
  setViewSize: (width: number, height: number) => void;
  /** Debounced 150 ms by useRhymeQuery; replaces the candidate set (offset 0). */
  runQuery: () => Promise<void>;
  /** Appends the next page. */
  loadMore: () => Promise<void>;
  dismiss: (id: string) => void;
  /** Creates a snippet from the candidate at `at` (world top-left) or at the card's own position. */
  promote: (id: string, at?: Point) => Promise<void>;
  setHovered: (id: string | null) => void;
  setPinned: (id: string | null) => void;
}

const CLEARED = {
  typedAnchor: '',
  anchors: [] as RhymeUnit[],
  candidates: [] as RhymeCandidate[],
  total: 0,
  offset: 0,
  positions: new Map<string, Point>(),
  dismissed: new Set<string>(),
  hoveredId: null,
  pinnedId: null,
  loading: false,
  error: null,
};

/** Monotonic query counter so a slow, older response never overwrites a newer one. */
let querySeq = 0;

function enabledSources(sources: RhymeState['sources']): SourceName[] {
  const out: SourceName[] = [];
  if (sources.notes) out.push('notes');
  if (sources.dictionary) out.push('dictionary');
  return out;
}

function viewportCenter(viewSize: RhymeState['viewSize']): Point {
  const { viewport } = useCanvasStore.getState();
  return {
    x: viewSize.width / (2 * viewport.zoom) - viewport.x,
    y: viewSize.height / (2 * viewport.zoom) - viewport.y,
  };
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

export const useRhymeStore = create<RhymeState>((set, get) => {
  /** Build the request for the current state, or null when nothing should be queried. */
  const buildRequest = (offset: number): { request: RhymeSuggestRequest; center: Point; slot: Slot | null } | null => {
    const state = get();
    const canvas = useCanvasStore.getState();
    if (!state.active || !canvas.canvasId) return null;
    const anchors = anchorsFromSelection(canvas.selectedSnippetIds, canvas.snippets, state.typedAnchor);
    const sources = enabledSources(state.sources);
    if (anchors.length === 0 || sources.length === 0) return null;

    const selected = selectedSnippets(canvas.selectedSnippetIds, canvas.snippets);
    const slot =
      state.slotMode === 'from-selection'
        ? selected.length > 0
          ? slotFromSnippet(selected[0], null)
          : null
        : state.slot;
    const center = anchorCenter(selected, viewportCenter(state.viewSize));
    return {
      request: {
        canvas_id: canvas.canvasId,
        anchors,
        context: [],
        slot,
        k: state.k,
        min_score: state.minScore,
        sources,
        include_rare: false,
        exclude_ids: Array.from(state.dismissed),
        limit: RHYME_PAGE_SIZE,
        offset,
        weights: null,
        dialect: null,
      },
      center,
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
    viewSize: { width: 800, height: 600 },
    ...CLEARED,

    enter: () => set({ active: true, error: null }),

    exit: () => {
      querySeq++;
      set({ active: false, ...CLEARED, positions: new Map(), dismissed: new Set() });
    },

    setTypedAnchor: (t) => set({ typedAnchor: t }),
    setK: (k) => set({ k }),
    setMinScore: (v) => set({ minScore: Math.max(0, Math.min(1, v)) }),
    setSlot: (s) => set({ slot: s }),
    setSlotMode: (m) => set({ slotMode: m }),
    toggleSource: (name) => set((s) => ({ sources: { ...s.sources, [name]: !s.sources[name] } })),
    setViewSize: (width, height) => set({ viewSize: { width, height } }),

    runQuery: async () => {
      const built = buildRequest(0);
      const seq = ++querySeq;
      if (!built) {
        if (get().active) {
          set({ anchors: [], candidates: [], total: 0, offset: 0, loading: false, error: null, hoveredId: null, pinnedId: null });
        }
        return;
      }
      set({ loading: true, error: null, slot: built.slot });
      try {
        const res = await nlpApi.rhymeSuggest(built.request);
        if (seq !== querySeq) return;
        const { dismissed, positions } = get();
        const candidates = res.data.candidates.filter((c) => !dismissed.has(c.id));
        set({
          anchors: res.data.anchors,
          candidates,
          total: res.data.total,
          offset: 0,
          positions: layoutCandidates(candidates, built.center, positions),
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
        const { candidates, dismissed, positions } = get();
        const seen = new Set(candidates.map((c) => c.id));
        const fresh = res.data.candidates.filter((c) => !dismissed.has(c.id) && !seen.has(c.id));
        const merged = [...candidates, ...fresh];
        set({
          candidates: merged,
          total: res.data.total,
          offset: nextOffset,
          positions: layoutCandidates(fresh, built.center, positions),
          loading: false,
          error: null,
        });
      } catch (err) {
        if (seq !== querySeq) return;
        set({ loading: false, error: toRhymeError(err, 'query') });
      }
    },

    dismiss: (id) => {
      const { candidates, dismissed, hoveredId, pinnedId } = get();
      const next = new Set(dismissed);
      next.add(id);
      set({
        candidates: candidates.filter((c) => c.id !== id),
        dismissed: next,
        hoveredId: hoveredId === id ? null : hoveredId,
        pinnedId: pinnedId === id ? null : pinnedId,
      });
    },

    promote: async (id, at) => {
      const { candidates, positions, anchors, k } = get();
      const candidate = candidates.find((c) => c.id === id);
      if (!candidate) return;
      const center = positions.get(id) ?? { x: 0, y: 0 };
      const topLeft = at ?? candidateTopLeft(center, candidateCardSize(candidate));
      try {
        const created = await useCanvasStore
          .getState()
          .addSnippetFromText(candidate.text, topLeft.x, topLeft.y, derivedFromFor(candidate, anchors, k));
        if (created === null) return;
        get().dismiss(id);
        set({ error: null });
      } catch (err) {
        set({ error: toRhymeError(err, 'promote') });
      }
    },

    setHovered: (id) => set({ hoveredId: id }),
    setPinned: (id) => set({ pinnedId: id }),
  };
});
