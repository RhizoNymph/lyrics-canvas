/**
 * Multi-selection in the word list: click selects one, Ctrl/Cmd-click (or the checkbox) toggles,
 * Shift-click selects the range from the anchor in list order. The selection is pruned to the words
 * still shown whenever the bank or the filter changes.
 */
export interface WordSelection {
  readonly ids: ReadonlySet<string>;
  /** The last clicked or toggled word, where a Shift range starts. */
  readonly anchor: string | null;
}

export const EMPTY_SELECTION: WordSelection = { ids: new Set(), anchor: null };

export type SelectionAction =
  | { readonly type: 'click'; readonly id: string }
  | { readonly type: 'toggle'; readonly id: string }
  | { readonly type: 'range'; readonly id: string; readonly order: readonly string[] }
  | { readonly type: 'add'; readonly ids: readonly string[] }
  | { readonly type: 'set'; readonly ids: readonly string[] }
  | { readonly type: 'clear' }
  | { readonly type: 'prune'; readonly keep: ReadonlySet<string> };

export function selectionReducer(state: WordSelection, action: SelectionAction): WordSelection {
  switch (action.type) {
    case 'click':
      return { ids: new Set([action.id]), anchor: action.id };
    case 'toggle': {
      const ids = new Set(state.ids);
      if (ids.has(action.id)) ids.delete(action.id);
      else ids.add(action.id);
      return { ids, anchor: action.id };
    }
    case 'range': {
      const from = state.anchor === null ? -1 : action.order.indexOf(state.anchor);
      const to = action.order.indexOf(action.id);
      if (from < 0 || to < 0) return { ids: new Set([action.id]), anchor: action.id };
      const [lo, hi] = from <= to ? [from, to] : [to, from];
      return { ids: new Set(action.order.slice(lo, hi + 1)), anchor: state.anchor };
    }
    case 'add':
      return { ids: new Set([...state.ids, ...action.ids]), anchor: state.anchor };
    case 'set':
      return { ids: new Set(action.ids), anchor: action.ids[action.ids.length - 1] ?? null };
    case 'clear':
      return EMPTY_SELECTION;
    case 'prune': {
      const anchorGone = state.anchor !== null && !action.keep.has(state.anchor);
      const kept = [...state.ids].filter((id) => action.keep.has(id));
      if (kept.length === state.ids.size && !anchorGone) return state;
      return { ids: new Set(kept), anchor: anchorGone ? null : state.anchor };
    }
  }
}

export type ClickKind = 'click' | 'toggle' | 'range';

export function clickKind(e: { readonly shiftKey: boolean; readonly ctrlKey: boolean; readonly metaKey: boolean }): ClickKind {
  if (e.shiftKey) return 'range';
  if (e.ctrlKey || e.metaKey) return 'toggle';
  return 'click';
}
