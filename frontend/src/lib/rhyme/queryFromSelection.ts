/**
 * Builds the `anchors` of a RhymeSuggestRequest from the canvas selection and the
 * panel's typed phrase. Snippet anchors use `line_index: null` (last non-empty line).
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

export function anchorsFromSelection(
  selectedIds: ReadonlySet<string>,
  snippets: ReadonlyMap<string, Snippet>,
  typedText: string
): AnchorRef[] {
  const anchors: AnchorRef[] = selectedSnippets(selectedIds, snippets)
    .filter((s) => s.raw_text.trim().length > 0)
    .map((s) => ({ kind: 'snippet', snippet_id: s.id, line_index: null }));
  const text = typedText.trim();
  if (text.length > 0) anchors.push({ kind: 'text', text });
  return anchors;
}
