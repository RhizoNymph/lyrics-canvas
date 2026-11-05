import { create } from 'zustand';
import { wordBankApi } from '@/api/client';
import {
  EMPTY_FILTER, WORD_BANK_FILTER_KEY, browserFilterStorage, filterReducer, loadFilterAt, saveFilterAt, toPersisted,
} from '@/lib/tagFilter';
import type { FilterAction, FilterState } from '@/lib/tagFilter';
import { planTagToggle } from '@/lib/tags';
import {
  EMPTY_SELECTION, WordBankApiError, addSummary, checkBatch, checkWordText, describeWordBankError, describeWordProblem,
  isDraftCreatable, mergeWords, patchWordTag, removeWordTag, removeWords, selectionReducer, splitAddInput, tagLinkDelta,
  toWordBankApiError, visibleWords,
} from '@/lib/wordBank';
import type { SelectionAction, SnippetDraft, WordBankOperation, WordBankResult, WordSelection } from '@/lib/wordBank';
import { newSnippetPosition } from '@/lib/voice/target';
import type { Snippet, SnippetTag } from '@/types';
import type { BankWord, BankWordCreateResponse, WordTagOp } from '@/types/wordBank';
import { useCanvasStore } from './canvasStore';
import { useTagStore } from './tagStore';

export type WordBankLoadState = 'idle' | 'loading' | 'ready' | 'failed';

/** A transient result line ("Added 2, 1 already in bank", or an error), shown as a toast. */
export interface WordBankNotice {
  readonly id: number;
  readonly tone: 'info' | 'error';
  readonly text: string;
}

/** Where an add came from; only changes the wording of a failure. */
export type AddSource = 'box' | 'rhyme' | 'editor' | 'related';

/**
 * The user's word bank (user-wide, loaded whole and filtered locally), its tag filter (persisted at
 * `lyrics-canvas.wordBankFilter`), the selection, and every word bank write. Actions resolve to a
 * WordBankResult and never throw. Invariant: `selection` only holds ids of words the filter shows.
 */
export interface WordBankState {
  /** Newest first, like the server. */
  words: readonly BankWord[];
  loadState: WordBankLoadState;
  loadError: WordBankApiError | null;
  filter: FilterState;
  filterRestored: boolean;
  selection: WordSelection;
  /** Tags the add box (and the related "+") link to new words. */
  addTagIds: readonly string[];
  /** Bumped on every change to the bank's words or their tags, so rhyme views refetch. */
  revision: number;
  notice: WordBankNotice | null;

  /** Restore the persisted filter (first call only) and load the bank. */
  open: () => Promise<WordBankResult<void>>;
  load: () => Promise<WordBankResult<void>>;
  dispatchFilter: (action: FilterAction) => void;
  select: (action: SelectionAction) => void;
  setAddTagIds: (ids: readonly string[]) => void;
  /** Validate locally (first bad index wins), then POST the batch with `tagIds`. */
  addWords: (raw: readonly string[], tagIds: readonly string[]) => Promise<WordBankResult<BankWordCreateResponse>>;
  /**
   * Split `text` on commas and newlines and add it with the given tags. Posts the result as a notice
   * (failures too, except for the add box, which shows them inline).
   */
  addFromText: (text: string, source: AddSource, tagIds?: readonly string[]) => Promise<WordBankResult<BankWordCreateResponse>>;
  renameWord: (id: string, text: string) => Promise<WordBankResult<BankWord>>;
  deleteWords: (ids: readonly string[]) => Promise<WordBankResult<void>>;
  setTag: (wordIds: readonly string[], tagId: string, op: WordTagOp) => Promise<WordBankResult<void>>;
  /** The tag picker click on the selection: unlink when every selected word has it, else link where missing. */
  toggleTagOnSelection: (tagId: string) => Promise<WordBankResult<void>>;
  /** Create the draft as a snippet at the viewport centre of the open canvas. */
  makeSnippet: (draft: SnippetDraft) => Promise<WordBankResult<Snippet>>;
  dismissNotice: (id: number) => void;

  // Kept in step by tagStore.
  patchTag: (tag: SnippetTag) => void;
  removeTag: (tagId: string) => void;
}

let noticeSeq = 0;

function fail<T>(error: WordBankApiError): WordBankResult<T> {
  console.warn('word bank: request failed', { operation: error.operation, kind: error.kind, status: error.status });
  return { ok: false, error };
}

function localInvalid(operation: WordBankOperation, message: string, index: number | null): WordBankApiError {
  return new WordBankApiError({ kind: 'invalid_word', index }, operation, message);
}

function prunedSelection(selection: WordSelection, words: readonly BankWord[], filter: FilterState): WordSelection {
  const keep = new Set(visibleWords(words, filter.applied).map((w) => w.id));
  return selectionReducer(selection, { type: 'prune', keep });
}

/** Adjust tagStore's word_count for every tag whose links changed between the two versions. */
function syncWordCounts(before: readonly BankWord[], after: readonly BankWord[], tagIds: Iterable<string>): void {
  const tags = useTagStore.getState();
  for (const tagId of new Set(tagIds)) {
    const delta = tagLinkDelta(before, after, tagId);
    if (delta !== 0) tags.adjustWordCount(tagId, delta);
  }
}

export const useWordBankStore = create<WordBankState>((set, get) => {
  /** Replace the words; the selection follows (pruned) and the revision bumps. */
  const setWords = (words: readonly BankWord[]) => {
    const { selection, filter, revision } = get();
    set({ words, selection: prunedSelection(selection, words, filter), revision: revision + 1 });
  };

  const notify = (tone: WordBankNotice['tone'], text: string) => {
    noticeSeq += 1;
    set({ notice: { id: noticeSeq, tone, text } });
  };

  return {
    words: [],
    loadState: 'idle',
    loadError: null,
    filter: EMPTY_FILTER,
    filterRestored: false,
    selection: EMPTY_SELECTION,
    addTagIds: [],
    revision: 0,
    notice: null,

    open: async () => {
      if (!get().filterRestored) {
        const persisted = loadFilterAt(browserFilterStorage(), WORD_BANK_FILTER_KEY);
        set({ filter: filterReducer(EMPTY_FILTER, { type: 'restore', persisted }), filterRestored: true });
      }
      return get().load();
    },

    load: async () => {
      set({ loadState: 'loading', loadError: null });
      try {
        const words = await wordBankApi.list();
        setWords(words);
        set({ loadState: 'ready' });
        return { ok: true, value: undefined };
      } catch (err) {
        const error = toWordBankApiError(err, 'load_words');
        set({ loadState: 'failed', loadError: error });
        return fail(error);
      }
    },

    dispatchFilter: (action) => {
      const { filter, selection, words } = get();
      const next = filterReducer(filter, action);
      if (next === filter) return;
      set({ filter: next, selection: prunedSelection(selection, words, next) });
      // A failed write is already logged; the in-memory filter still applies for this session.
      saveFilterAt(browserFilterStorage(), WORD_BANK_FILTER_KEY, toPersisted(next));
    },

    select: (action) => {
      const { selection, words, filter } = get();
      const next = selectionReducer(selection, action);
      // Only shown words can be selected (a match or family member can be filtered out meanwhile).
      set({ selection: prunedSelection(next, words, filter) });
    },

    setAddTagIds: (ids) => set({ addTagIds: [...new Set(ids)] }),

    addWords: async (raw, tagIds) => {
      const check = checkBatch(raw);
      if (!check.ok) {
        switch (check.reason) {
          case 'empty':
            return fail(localInvalid('add_words', 'Type a word', null));
          case 'too_many':
            return fail(new WordBankApiError({ kind: 'too_many_words' }, 'add_words', 'At most 200 words at once'));
          case 'invalid_word':
            return fail(localInvalid('add_words', describeWordProblem(check.problem), check.index));
        }
      }
      let response: BankWordCreateResponse;
      try {
        response = await wordBankApi.add({ words: [...check.words], tag_ids: [...tagIds] });
      } catch (err) {
        return fail(toWordBankApiError(err, 'add_words'));
      }
      const before = get().words;
      const incoming = [...response.created, ...response.existing];
      setWords(mergeWords(before, incoming));
      syncWordCounts(before, incoming, tagIds);
      return { ok: true, value: response };
    },

    addFromText: async (text, source, tagIds = []) => {
      const raw = splitAddInput(text);
      const result = await get().addWords(raw, tagIds);
      if (result.ok) notify('info', addSummary(result.value));
      // The add box shows its failures inline; the other sources only have the toast.
      else if (source !== 'box') notify('error', `Not added to the word bank: ${describeWordBankError(result.error, raw)}`);
      return result;
    },

    renameWord: async (id, text) => {
      const current = get().words.find((w) => w.id === id);
      const check = checkWordText(text);
      if (!check.ok) return fail(localInvalid('rename_word', describeWordProblem(check.problem), null));
      if (current !== undefined && current.text === check.text) return { ok: true, value: current };
      try {
        const updated = await wordBankApi.rename(id, check.text);
        setWords(mergeWords(get().words, [updated]));
        return { ok: true, value: updated };
      } catch (err) {
        const error = toWordBankApiError(err, 'rename_word');
        if (error.kind === 'word_not_found') setWords(removeWords(get().words, new Set([id])));
        return fail(error);
      }
    },

    deleteWords: async (ids) => {
      if (ids.length === 0) return { ok: true, value: undefined };
      try {
        await wordBankApi.delete(ids);
      } catch (err) {
        return fail(toWordBankApiError(err, 'delete_words'));
      }
      const gone = new Set(ids);
      const before = get().words;
      const removed = before.filter((w) => gone.has(w.id));
      setWords(removeWords(before, gone));
      const tags = useTagStore.getState();
      const counts = new Map<string, number>();
      for (const w of removed) for (const t of w.tags) counts.set(t.id, (counts.get(t.id) ?? 0) + 1);
      for (const [tagId, n] of counts) tags.adjustWordCount(tagId, -n);
      return { ok: true, value: undefined };
    },

    setTag: async (wordIds, tagId, op) => {
      if (wordIds.length === 0) return { ok: true, value: undefined };
      let updated: BankWord[];
      try {
        updated = (await wordBankApi.setTag(wordIds, tagId, op)).words;
      } catch (err) {
        const error = toWordBankApiError(err, op === 'link' ? 'link_words' : 'unlink_words');
        // The tag is gone on the server: converge everywhere (snippets, words, filters keep their text).
        if (error.kind === 'tag_not_found') useTagStore.getState().forgetTag(tagId);
        return fail(error);
      }
      const before = get().words;
      setWords(mergeWords(before, updated));
      syncWordCounts(before, updated, [tagId]);
      return { ok: true, value: undefined };
    },

    toggleTagOnSelection: async (tagId) => {
      const { words, selection } = get();
      const targets = words.filter((w) => selection.ids.has(w.id));
      const plan = planTagToggle(tagId, targets);
      return get().setTag(plan.targetIds, tagId, plan.action);
    },

    makeSnippet: async (draft) => {
      if (!isDraftCreatable(draft.text)) return fail(localInvalid('make_snippet', 'The draft is empty', null));
      const canvas = useCanvasStore.getState();
      if (canvas.canvasId === null) {
        return fail(new WordBankApiError({ kind: 'not_found' }, 'make_snippet', 'No canvas is open'));
      }
      const at = newSnippetPosition(canvas.viewport, canvas.canvasViewSize);
      try {
        const snippet = await canvas.addSnippetFromText(draft.text, at.x, at.y, undefined, draft.type);
        if (snippet === null) return fail(new WordBankApiError({ kind: 'not_found' }, 'make_snippet', 'No canvas is open'));
        useCanvasStore.getState().setSelection(new Set([snippet.id]));
        return { ok: true, value: snippet };
      } catch (err) {
        return fail(toWordBankApiError(err, 'make_snippet'));
      }
    },

    dismissNotice: (id) => {
      if (get().notice?.id === id) set({ notice: null });
    },

    patchTag: (tag) => {
      const next = patchWordTag(get().words, tag);
      if (next !== get().words) setWords(next);
    },

    removeTag: (tagId) => {
      const next = removeWordTag(get().words, tagId);
      if (next !== get().words) setWords(next);
      const ids = get().addTagIds;
      if (ids.includes(tagId)) set({ addTagIds: ids.filter((id) => id !== tagId) });
    },
  };
});

/** The words the filter shows, in list order (memoize in components on `words` and `filter.applied`). */
export function selectVisibleWords(state: Pick<WordBankState, 'words' | 'filter'>): readonly BankWord[] {
  return visibleWords(state.words, state.filter.applied);
}
