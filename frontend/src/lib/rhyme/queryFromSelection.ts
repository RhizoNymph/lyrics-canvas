/**
 * Builds the `anchors` of a RhymeSuggestRequest from the canvas selection and the
 * sidebar's typed phrase. Snippet anchors use `line_index: null` (last non-empty line); ids in
 * `excluded` (chips removed for this query) are skipped.
 */
import type { Snippet } from '@/types';
import type { AnchorRef } from '@/types/rhyme';

/** Selected snippets that exist, in id order for deterministic requests. */
export function selectedSnippets(selectedIds: ReadonlySet<string>, snippets: ReadonlyMap<string, Snippet>): Snippet[] {
  return Array.from(selectedIds)
    .sort()
    .map((id) => snippets.get(id))
    .filter((s): s is Snippet => s !== undefined);
}

/** Selected snippets that act as anchors: present, non-blank, and not excluded via a chip. */
export function anchorSnippets(
  selectedIds: ReadonlySet<string>,
  snippets: ReadonlyMap<string, Snippet>,
  excluded: ReadonlySet<string>
): Snippet[] {
  return selectedSnippets(selectedIds, snippets).filter((s) => s.raw_text.trim().length > 0 && !excluded.has(s.id));
}

export function anchorsFromSelection(
  selectedIds: ReadonlySet<string>,
  snippets: ReadonlyMap<string, Snippet>,
  typedText: string,
  excluded: ReadonlySet<string> = new Set()
): AnchorRef[] {
  const anchors: AnchorRef[] = anchorSnippets(selectedIds, snippets, excluded).map((s) => ({
    kind: 'snippet',
    snippet_id: s.id,
    line_index: null,
  }));
  const text = typedText.trim();
  if (text.length > 0) anchors.push({ kind: 'text', text });
  return anchors;
}
