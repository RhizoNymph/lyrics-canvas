/**
 * The filter bar's state machine. The input text may be invalid at any moment; the applied filter only
 * ever changes to a text that parses, so an invalid edit keeps the last valid filter in force.
 */
import { parse, renameInText } from '@/lib/tagExpr';
import type { TagExprError, TagExprOrEmpty } from '@/lib/tagExpr';

export interface FilterState {
  /** What the input shows. */
  readonly text: string;
  /** Why `text` does not parse; null when it does. */
  readonly error: TagExprError | null;
  /** The last text that parsed (as typed, not normalized). */
  readonly appliedText: string;
  /** `appliedText` parsed; null = no filter (everything shows). */
  readonly applied: TagExprOrEmpty;
}

/** What is persisted per canvas. */
export interface PersistedFilter {
  readonly text: string;
  readonly appliedText: string;
}

export type FilterAction =
  | { readonly type: 'edit'; readonly text: string }
  | { readonly type: 'clear' }
  | { readonly type: 'applyPreset'; readonly expression: string }
  | { readonly type: 'renameTag'; readonly from: string; readonly to: string }
  | { readonly type: 'restore'; readonly persisted: PersistedFilter | null };

export const EMPTY_FILTER: FilterState = { text: '', error: null, appliedText: '', applied: null };

function withText(state: FilterState, text: string): FilterState {
  const parsed = parse(text);
  if (parsed.ok) return { text, error: null, appliedText: text, applied: parsed.expr };
  return { ...state, text, error: parsed.error };
}

export function restoreFilter(persisted: PersistedFilter | null): FilterState {
  if (persisted === null) return EMPTY_FILTER;
  const applied = parse(persisted.appliedText);
  const base: FilterState = applied.ok
    ? { text: persisted.appliedText, error: null, appliedText: persisted.appliedText, applied: applied.expr }
    : EMPTY_FILTER;
  return withText(base, persisted.text);
}

export function filterReducer(state: FilterState, action: FilterAction): FilterState {
  switch (action.type) {
    case 'edit':
      return withText(state, action.text);
    case 'applyPreset':
      return withText(state, action.expression);
    case 'clear':
      return EMPTY_FILTER;
    case 'renameTag':
      return restoreFilter({
        text: renameInText(state.text, action.from, action.to),
        appliedText: renameInText(state.appliedText, action.from, action.to),
      });
    case 'restore':
      return restoreFilter(action.persisted);
  }
}

export function toPersisted(state: FilterState): PersistedFilter {
  return { text: state.text, appliedText: state.appliedText };
}

/** The input shows something other than what is applied (it is invalid). */
export function isStale(state: FilterState): boolean {
  return state.error !== null;
}
