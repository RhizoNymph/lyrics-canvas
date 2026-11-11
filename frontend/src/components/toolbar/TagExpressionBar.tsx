import { useMemo } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { useTagFilterStore } from '@/stores/tagFilterStore';
import { useCanvasSearchStore } from '@/stores/canvasSearchStore';
import { visibleSnippets } from '@/lib/canvas';
import { filterCountLabels, isSearchActive } from '@/lib/search';
import TagFilterBar from '@/components/tags/TagFilterBar';
import CanvasSearchBox from '@/components/search/CanvasSearchBox';

/**
 * The canvas page's filter row (canvas and list view): the in-canvas search box, then the shared
 * TagFilterBar bound to tagFilterStore. Both filters apply together (AND); both counts say what they leave.
 * An invalid expression keeps the last valid tag filter applied.
 */
export default function TagExpressionBar() {
  const filter = useTagFilterStore((s) => s.filter);
  const dispatch = useTagFilterStore((s) => s.dispatch);
  const search = useCanvasSearchStore((s) => s.query);
  const snippets = useCanvasStore((s) => s.snippets);

  const shown = useMemo(() => visibleSnippets(snippets, filter.applied, search).length, [snippets, filter.applied, search]);
  const labels = filterCountLabels({ total: snippets.size, shown, tagActive: filter.applied !== null, searchActive: isSearchActive(search) });

  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 16, padding: '4px 16px', borderBottom: '1px solid #2a2a4a', background: '#16162a' }}>
      <CanvasSearchBox countLabel={labels.search} />
      <TagFilterBar
        filter={filter}
        dispatch={dispatch}
        countLabel={labels.tag}
        ariaLabel="Tag filter expression"
        noTagsHint="No tags yet. Right-click a snippet to tag it."
        pillTitle={(tag) => `Insert "${tag.name}" at the caret (${tag.snippet_count} snippets)`}
        style={{ flex: 1, minWidth: 0 }}
      />
    </div>
  );
}
