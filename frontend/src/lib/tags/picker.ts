/**
 * Tag picker logic: which snippets a toggle applies to, the checkbox state per tag across them, what a
 * click does, search ranking, and whether "Create '<query>'" is offered.
 */
import type { Snippet, Tag } from '@/types';
import { checkName, sameName } from './names';
import type { NameProblem } from './names';

/**
 * The snippets the picker edits: the whole multi-selection when the menu was opened on one of its
 * members, else just that snippet. Ids that no longer exist are dropped.
 */
export function pickerTargets(
  contextSnippetId: string,
  selection: ReadonlySet<string>,
  snippets: ReadonlyMap<string, Snippet>,
): Snippet[] {
  const ids = selection.size > 1 && selection.has(contextSnippetId) ? [...selection] : [contextSnippetId];
  return ids.map((id) => snippets.get(id)).filter((s): s is Snippet => s !== undefined);
}

export type CheckState = 'all' | 'some' | 'none';

export function checkState(tagId: string, targets: readonly Snippet[]): CheckState {
  const n = targets.filter((s) => s.tags.some((t) => t.id === tagId)).length;
  if (n === 0) return 'none';
  return n === targets.length ? 'all' : 'some';
}

export interface TogglePlan {
  readonly action: 'link' | 'unlink';
  readonly tagId: string;
  /** Only the snippets whose state changes. */
  readonly snippetIds: readonly string[];
}

/** Checked everywhere -> unlink from all; otherwise (unchecked or mixed) -> link where missing. */
export function planToggle(tagId: string, targets: readonly Snippet[]): TogglePlan {
  if (checkState(tagId, targets) === 'all') {
    return { action: 'unlink', tagId, snippetIds: targets.map((s) => s.id) };
  }
  return { action: 'link', tagId, snippetIds: targets.filter((s) => !s.tags.some((t) => t.id === tagId)).map((s) => s.id) };
}

/** Tags whose name contains the query (case-insensitive), prefix matches first; input order otherwise. */
export function searchTags(tags: readonly Tag[], query: string): Tag[] {
  const q = query.trim().toLowerCase();
  if (q === '') return [...tags];
  const prefix: Tag[] = [];
  const inner: Tag[] = [];
  for (const tag of tags) {
    const name = tag.name.toLowerCase();
    if (name.startsWith(q)) prefix.push(tag);
    else if (name.includes(q)) inner.push(tag);
  }
  return [...prefix, ...inner];
}

export type CreateOffer =
  | { kind: 'none' }
  | { kind: 'create'; name: string }
  | { kind: 'invalid'; problem: Exclude<NameProblem, 'empty'> };

/** "Create '<query>'" is offered for a valid name that no tag has (case-insensitively). */
export function createOffer(query: string, tags: readonly Tag[]): CreateOffer {
  const check = checkName(query);
  if (!check.ok) return check.problem === 'empty' ? { kind: 'none' } : { kind: 'invalid', problem: check.problem };
  if (tags.some((t) => sameName(t.name, check.name))) return { kind: 'none' };
  return { kind: 'create', name: check.name };
}
