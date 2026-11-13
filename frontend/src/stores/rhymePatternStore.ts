import { create } from 'zustand';
import { rhymePatternApi } from '@/api/client';
import { useCanvasStore } from './canvasStore';
import { useRhymeStore } from './rhymeStore';
import { useWordBankStore } from './wordBankStore';
import { format, parse } from '@/lib/tagExpr';
import { materializePosition } from '@/lib/rhyme/materializePosition';
import {
  EMPTY_PICKS, PatternError, appendLineMatches, appendSlotPage, applySlotEdit, buildDetectRequest, buildSuggestRequest,
  describeSlotEditError, dismissFromResults, firstPageResults, initialEditState, letterLookup, lineMatchDerivedFrom, pickTexts,
  prunePicks, serverSlotsOf, slotLetter, toPatternError, togglePick, trayEntries, tupleText,
} from '@/lib/rhyme/pattern';
import type { LineRange, PatternParams, PatternResults, PatternSession, SlotEdit, SlotEditState, SuggestPage, TuplePicks } from '@/lib/rhyme/pattern';
import { PATTERN_DETECT_MIN_SCORE, PATTERN_DETECT_MIN_SCORE_FLOOR, PATTERN_LINE_MATCH_PAGE_SIZE, PATTERN_PAGE_SIZE } from '@/types/rhymePattern';
import type { PatternCandidate, PatternLine, PatternSlot } from '@/types/rhymePattern';
import type { SourceName } from '@/types/rhyme';

/**
 * The pattern view's lifecycle. `off`: the sidebar is in its normal (single-anchor) mode. Everything else
 * belongs to one session (snippet + line range); `ready` carries the lines, the editable slots, the latest
 * results and the tuple picks, so none of them can exist without a detected session.
 */
export type PatternView =
  | { readonly kind: 'off' }
  | { readonly kind: 'detecting'; readonly session: PatternSession }
  | { readonly kind: 'detect_failed'; readonly session: PatternSession; readonly error: PatternError }
  | {
      readonly kind: 'ready';
      readonly session: PatternSession;
      readonly lines: readonly PatternLine[];
      readonly edit: SlotEditState;
      /** The slots as detected (legend keys before the first suggest answers). */
      readonly detected: ReadonlyMap<string, PatternSlot>;
      readonly results: PatternResults | null;
      readonly picks: TuplePicks;
    };

export type ReadyView = Extract<PatternView, { kind: 'ready' }>;

/** The first-page suggest call. `idle`: nothing to ask (no on slot, or every source off). */
export type SuggestStatus =
  | { readonly kind: 'idle' }
  | { readonly kind: 'loading' }
  | { readonly kind: 'done' }
  | { readonly kind: 'failed'; readonly error: PatternError };

/** Key of the line-match pager in `paging` (slot ids are "s"/"u" prefixed, so no clash). */
export const LINES_PAGER = 'lines';

export interface RhymePatternState {
  view: PatternView;
  /** Detection threshold; kept across sessions like the rhyme params. */
  detectMinScore: number;
  /** Rows dismissed this session (sent as `exclude_ids`). */
  dismissed: ReadonlySet<string>;
  suggest: SuggestStatus;
  /** Pagers with a page in flight: slot ids and LINES_PAGER. */
  paging: ReadonlySet<string>;
  /** Why the last slot edit was refused, or what an action did. */
  notice: string | null;
  /** Failure of a paging, make-snippet or materialize action (the suggest failure lives in `suggest`). */
  actionError: PatternError | null;
  /** Snippets created next to the anchor this session (drives the step-down). */
  createdCount: number;

  /** Start a session on a line range of a snippet of the open canvas, and detect its slots. */
  enter: (snippetId: string, range: LineRange) => void;
  /** Back to the normal mode; everything of the session is dropped. */
  exit: () => void;
  /** Re-run detection on the same range (discards slot edits). */
  reset: () => void;
  setDetectMinScore: (v: number) => void;
  /** Apply a slot edit; a refused one leaves the slots alone and sets `notice`. useRhymePatternQuery re-queries. */
  edit: (e: SlotEdit) => void;
  /** First page of every on slot plus the line matches. Debounced by useRhymePatternQuery after edits. */
  runSuggest: () => Promise<void>;
  loadMoreSlot: (slotId: string) => Promise<void>;
  loadMoreLines: () => Promise<void>;
  dismiss: (id: string) => void;
  togglePick: (slotId: string, candidate: PatternCandidate) => void;
  clearPicks: () => void;
  /** Create a snippet next to the anchor with one line: the picks in slot order. */
  makeSnippet: () => Promise<void>;
  addPicksToBank: () => Promise<void>;
  /** Create a snippet from a whole-line match, then remove the row. */
  materializeLine: (unitId: string) => Promise<void>;
  clearNotice: () => void;
}

function cleared() {
  return {
    view: { kind: 'off' } as PatternView,
    dismissed: new Set<string>() as ReadonlySet<string>,
    suggest: { kind: 'idle' } as SuggestStatus,
    paging: new Set<string>() as ReadonlySet<string>,
    notice: null,
    actionError: null,
    createdCount: 0,
  };
}

/** Monotonic counters: a response is applied only if no newer detect / suggest started since. */
let detectSeq = 0;
let suggestSeq = 0;

function enabledSources(sources: { notes: boolean; dictionary: boolean }): SourceName[] {
  const out: SourceName[] = [];
  if (sources.notes) out.push('notes');
  if (sources.dictionary) out.push('dictionary');
  return out;
}

/** The shared rhyme controls as request params, or the local tag-filter refusal. */
function currentParams(dismissed: ReadonlySet<string>): PatternParams | PatternError {
  const rhyme = useRhymeStore.getState();
  const filter = parse(rhyme.tagFilterText);
  if (!filter.ok) return new PatternError('invalid_tag_filter', 'suggest', `Tag filter: ${filter.error.message}`, null, filter.error);
  return {
    minScore: rhyme.minScore,
    sources: enabledSources(rhyme.sources),
    includeRare: rhyme.includeRare,
    tagFilter: filter.expr === null ? null : format(filter.expr),
    excludeIds: [...dismissed],
  };
}

/** The latest server view of every slot: suggest results over the detected ones. */
export function knownSlots(view: ReadyView): Map<string, PatternSlot> {
  const out = new Map(view.detected);
  if (view.results !== null) for (const [id, slot] of serverSlotsOf(view.results)) out.set(id, slot);
  return out;
}

/** The tray for the view: on slots in order with their picks. */
export function trayOf(view: ReadyView) {
  return trayEntries(view.edit.slots.map((s, i) => ({ id: s.id, letter: slotLetter(i), on: s.on })), view.picks);
}

export const useRhymePatternStore = create<RhymePatternState>((set, get) => {
  const ready = (): ReadyView | null => {
    const { view } = get();
    return view.kind === 'ready' ? view : null;
  };

  /** Replace the ready view, if the session is still the same one. */
  const patchReady = (session: PatternSession, patch: (v: ReadyView) => Partial<ReadyView>) => {
    const v = ready();
    if (v === null || v.session !== session) return;
    set({ view: { ...v, ...patch(v) } });
  };

  const detect = async (session: PatternSession) => {
    const seq = ++detectSeq;
    suggestSeq++;
    set({ view: { kind: 'detecting', session }, suggest: { kind: 'idle' }, paging: new Set(), notice: null, actionError: null });
    try {
      const res = await rhymePatternApi.detect(buildDetectRequest(session, get().detectMinScore));
      if (seq !== detectSeq) return;
      set({
        view: {
          kind: 'ready',
          session,
          lines: res.lines,
          edit: initialEditState(res.lines, res.slots),
          detected: new Map(res.slots.map((s) => [s.id, s])),
          results: null,
          picks: EMPTY_PICKS,
        },
      });
      void get().runSuggest();
    } catch (err) {
      if (seq !== detectSeq) return;
      set({ view: { kind: 'detect_failed', session, error: toPatternError(err, 'detect') } });
    }
  };

  /** Fetch one page; returns null when the view moved on (or nothing to ask). */
  const fetchPage = async (page: SuggestPage, seq: number) => {
    const v = ready();
    if (v === null) return null;
    const params = currentParams(get().dismissed);
    if (params instanceof PatternError) throw params;
    const req = buildSuggestRequest(v.session, v.edit, params, page);
    if (req === null) return null;
    const res = await rhymePatternApi.suggest(req, page.kind === 'first' ? 'suggest' : 'more');
    return seq === suggestSeq ? { view: v, res } : null;
  };

  const clearResults = () => {
    const v = ready();
    if (v !== null && v.results !== null) set({ view: { ...v, results: null } });
  };

  const setPaging = (key: string, on: boolean) => {
    const next = new Set(get().paging);
    if (on) next.add(key);
    else next.delete(key);
    set({ paging: next });
  };

  const createdAt = (session: PatternSession) => {
    const canvas = useCanvasStore.getState();
    const anchor = canvas.snippets.get(session.snippetId);
    return materializePosition({
      anchors: anchor === undefined ? [] : [anchor],
      counts: new Map([[session.snippetId, get().createdCount]]),
      viewport: canvas.viewport,
      viewSize: useRhymeStore.getState().viewSize,
    });
  };

  return {
    detectMinScore: PATTERN_DETECT_MIN_SCORE,
    ...cleared(),

    enter: (snippetId, range) => {
      const canvasId = useCanvasStore.getState().canvasId;
      if (!canvasId) return;
      set({ ...cleared() });
      void detect({ canvasId, snippetId, lineStart: range.lineStart, lineEnd: range.lineEnd });
    },

    exit: () => {
      detectSeq++;
      suggestSeq++;
      set({ ...cleared() });
    },

    reset: () => {
      const { view } = get();
      if (view.kind === 'off') return;
      void detect(view.session);
    },

    setDetectMinScore: (v) => set({ detectMinScore: Math.max(PATTERN_DETECT_MIN_SCORE_FLOOR, Math.min(1, v)) }),

    edit: (e) => {
      const v = ready();
      if (v === null) return;
      const result = applySlotEdit(v.edit, e);
      if (!result.ok) {
        set({ notice: describeSlotEditError(result.error, letterLookup(v.edit)) });
        return;
      }
      const ids = new Set(result.state.slots.map((s) => s.id));
      set({ view: { ...v, edit: result.state, picks: prunePicks(v.picks, ids) }, notice: null });
    },

    runSuggest: async () => {
      const seq = ++suggestSeq;
      if (ready() === null) return;
      set({ suggest: { kind: 'loading' }, paging: new Set(), actionError: null });
      try {
        const got = await fetchPage({ kind: 'first' }, seq);
        if (seq !== suggestSeq) return;
        if (got === null) {
          clearResults();
          set({ suggest: { kind: 'idle' } });
          return;
        }
        patchReady(got.view.session, () => ({ results: firstPageResults(got.res, get().dismissed) }));
        set({ suggest: { kind: 'done' } });
      } catch (err) {
        if (seq !== suggestSeq) return;
        clearResults();
        set({ suggest: { kind: 'failed', error: toPatternError(err, 'suggest') } });
      }
    },

    loadMoreSlot: async (slotId) => {
      const column = ready()?.results?.columns.get(slotId);
      if (column === undefined || get().paging.has(slotId) || get().suggest.kind === 'loading') return;
      const offset = column.offset + PATTERN_PAGE_SIZE;
      if (offset >= column.total) return;
      const seq = suggestSeq;
      setPaging(slotId, true);
      try {
        const got = await fetchPage({ kind: 'slot_more', slotId, offset }, seq);
        if (got === null) return;
        patchReady(got.view.session, (cur) => (cur.results === null ? {} : { results: appendSlotPage(cur.results, slotId, offset, got.res, get().dismissed) }));
      } catch (err) {
        if (seq === suggestSeq) set({ actionError: toPatternError(err, 'more') });
      } finally {
        if (seq === suggestSeq) setPaging(slotId, false);
      }
    },

    loadMoreLines: async () => {
      const results = ready()?.results ?? null;
      if (results === null || get().paging.has(LINES_PAGER) || get().suggest.kind === 'loading') return;
      const offset = results.lineMatchOffset + PATTERN_LINE_MATCH_PAGE_SIZE;
      if (offset >= results.lineMatchTotal) return;
      const seq = suggestSeq;
      setPaging(LINES_PAGER, true);
      try {
        const got = await fetchPage({ kind: 'line_more', offset }, seq);
        if (got === null) return;
        patchReady(got.view.session, (cur) => (cur.results === null ? {} : { results: appendLineMatches(cur.results, offset, got.res, get().dismissed) }));
      } catch (err) {
        if (seq === suggestSeq) set({ actionError: toPatternError(err, 'more') });
      } finally {
        if (seq === suggestSeq) setPaging(LINES_PAGER, false);
      }
    },

    dismiss: (id) => {
      const dismissed = new Set(get().dismissed);
      dismissed.add(id);
      set({ dismissed });
      const v = ready();
      if (v === null) return;
      const picks = new Map([...v.picks].filter(([, p]) => p.candidateId !== id));
      set({ view: { ...v, results: v.results === null ? null : dismissFromResults(v.results, id), picks: picks.size === v.picks.size ? v.picks : picks } });
    },

    togglePick: (slotId, candidate) => {
      const v = ready();
      if (v === null || !v.edit.slots.some((s) => s.id === slotId)) return;
      set({ view: { ...v, picks: togglePick(v.picks, slotId, { candidateId: candidate.id, text: candidate.text }) } });
    },

    clearPicks: () => {
      const v = ready();
      if (v !== null) set({ view: { ...v, picks: EMPTY_PICKS } });
    },

    makeSnippet: async () => {
      const v = ready();
      if (v === null) return;
      const text = tupleText(trayOf(v));
      if (text === '') return;
      const at = createdAt(v.session);
      try {
        const created = await useCanvasStore.getState().addSnippetFromText(text, at.x, at.y);
        if (created === null) return;
        set({ createdCount: get().createdCount + 1, actionError: null, notice: `Created "${text}".` });
      } catch (err) {
        set({ actionError: toPatternError(err, 'make_snippet') });
      }
    },

    addPicksToBank: async () => {
      const v = ready();
      if (v === null) return;
      const texts = pickTexts(trayOf(v));
      if (texts.length === 0) return;
      // The word bank store reports the outcome (and any failure) in its toast.
      await useWordBankStore.getState().addFromText(texts.join(', '), 'rhyme');
    },

    materializeLine: async (unitId) => {
      const v = ready();
      const match = v?.results?.lineMatches.find((m) => m.unit.id === unitId);
      if (v === null || match === undefined) return;
      const at = createdAt(v.session);
      try {
        const derived = lineMatchDerivedFrom(match, knownSlots(v), v.session.snippetId);
        const created = await useCanvasStore.getState().addSnippetFromText(match.unit.text, at.x, at.y, derived);
        if (created === null) return;
        set({ createdCount: get().createdCount + 1, actionError: null });
        get().dismiss(unitId);
      } catch (err) {
        set({ actionError: toPatternError(err, 'materialize') });
      }
    },

    clearNotice: () => set({ notice: null }),
  };
});
