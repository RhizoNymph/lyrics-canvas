import { useMemo } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useTagFilterStore } from '@/stores/tagFilterStore';
import { visibleSnippets } from '@/lib/canvas';
import TagFilterBar from '@/components/tags/TagFilterBar';

/**
 * The canvas page's tag filter (canvas and list view), the shared TagFilterBar bound to
 * tagFilterStore. An invalid expression keeps the last valid filter applied.
 */
export default function TagExpressionBar() {
  const filter = useTagFilterStore((s) => s.filter);
  const dispatch = useTagFilterStore((s) => s.dispatch);
  const snippets = useCanvasStore((s) => s.snippets);

  const shown = useMemo(() => visibleSnippets(snippets, filter.applied).length, [snippets, filter.applied]);

  return (
    <TagFilterBar
      filter={filter}
      dispatch={dispatch}
      countLabel={filter.applied === null ? `${snippets.size} snippets` : `${shown} of ${snippets.size} shown`}
      ariaLabel="Tag filter expression"
      noTagsHint="No tags yet. Right-click a snippet to tag it."
      pillTitle={(tag) => `Insert "${tag.name}" at the caret (${tag.snippet_count} snippets)`}
      style={{ padding: '4px 16px', borderBottom: '1px solid #2a2a4a', background: '#16162a' }}
    />
  );
}
