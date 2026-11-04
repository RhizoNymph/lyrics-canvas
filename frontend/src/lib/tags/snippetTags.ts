/** Immutable updates of the tags embedded in snippets (`Snippet.tags`). Unchanged maps are returned as is. */
import type { Snippet, SnippetTag, Tag } from '@/types';

export type SnippetMap = ReadonlyMap<string, Snippet>;

export function embedTag(tag: Tag | SnippetTag): SnippetTag {
  return { id: tag.id, name: tag.name, color: tag.color };
}

function mapSnippets(snippets: SnippetMap, update: (s: Snippet) => Snippet): Map<string, Snippet> | null {
  let next: Map<string, Snippet> | null = null;
  for (const [id, s] of snippets) {
    const u = update(s);
    if (u !== s) {
      next ??= new Map(snippets);
      next.set(id, u);
    }
  }
  return next;
}

/** Replace the embedded copy of `tag` (after a rename or recolor) in every snippet carrying it. */
export function patchEmbeddedTag(snippets: SnippetMap, tag: SnippetTag): SnippetMap {
  return (
    mapSnippets(snippets, (s) =>
      s.tags.some((t) => t.id === tag.id) ? { ...s, tags: s.tags.map((t) => (t.id === tag.id ? tag : t)) } : s,
    ) ?? snippets
  );
}

/** Drop the tag from every snippet (after it was deleted). */
export function removeEmbeddedTag(snippets: SnippetMap, tagId: string): SnippetMap {
  return (
    mapSnippets(snippets, (s) => (s.tags.some((t) => t.id === tagId) ? { ...s, tags: s.tags.filter((t) => t.id !== tagId) } : s)) ??
    snippets
  );
}

/** Link (append when missing) or unlink the tag on the given snippets. */
export function setEmbeddedTag(snippets: SnippetMap, ids: Iterable<string>, tag: SnippetTag, linked: boolean): SnippetMap {
  const wanted = new Set(ids);
  return (
    mapSnippets(snippets, (s) => {
      if (!wanted.has(s.id)) return s;
      const has = s.tags.some((t) => t.id === tag.id);
      if (linked && !has) return { ...s, tags: [...s.tags, tag] };
      if (!linked && has) return { ...s, tags: s.tags.filter((t) => t.id !== tag.id) };
      return s;
    }) ?? snippets
  );
}
