import { mergeHorizontal } from './mergeHorizontal';
import { mergeVertical } from './mergeVertical';
import { orderByPosition } from './order';
import type { MergeMode, MergedSnippet, NonEmptySnippets } from './types';

export { orderByPosition } from './order';
export { mergeVertical } from './mergeVertical';
export { mergeHorizontal } from './mergeHorizontal';
export { toMergeError } from './errors';
export { MergeError } from './types';
export type { MergeMode, MergedSnippet, MergeResult, MergeErrorKind, MergeErrorLevel, NonEmptySnippets } from './types';

/** Order by canvas position for the mode, then merge. */
export function mergeSnippets(snippets: NonEmptySnippets, mode: MergeMode): MergedSnippet {
  const ordered = orderByPosition(snippets, mode) as unknown as NonEmptySnippets;
  return mode === 'vertical' ? mergeVertical(ordered) : mergeHorizontal(ordered);
}
