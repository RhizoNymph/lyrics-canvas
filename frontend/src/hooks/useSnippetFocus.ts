import { useEffect } from 'react';
import type { SetURLSearchParams } from 'react-router-dom';
import { useCanvasStore } from '@/stores/canvasStore';
import { useCanvasSearchStore } from '@/stores/canvasSearchStore';
import { useTagFilterStore } from '@/stores/tagFilterStore';
import { useUIStore } from '@/stores/uiStore';
import { usePlacementNoticeStore } from '@/stores/placementNoticeStore';
import { FOCUS_PARAM, runSnippetFocus, snippetFocusParam, viewportCentredOn } from '@/lib/search';

/**
 * `/canvas/:id?snippet=<sid>` (a search hit): once the page has loaded that canvas (`loadedCanvasId`), run
 * the focus (lib/search/focus.ts `runSnippetFocus`) against the stores, then drop the parameter from the URL.
 */
export function useSnippetFocus(
  routeCanvasId: string | null,
  loadedCanvasId: string | null,
  params: URLSearchParams,
  setParams: SetURLSearchParams,
): void {
  const snippetId = snippetFocusParam(params);

  useEffect(() => {
    if (snippetId === null || routeCanvasId === null || loadedCanvasId !== routeCanvasId) return;
    const canvas = useCanvasStore.getState();
    if (canvas.canvasId !== routeCanvasId) return;
    const search = useCanvasSearchStore.getState();
    runSnippetFocus(snippetId, {
      snippets: canvas.snippets,
      tagFilter: useTagFilterStore.getState().filter.applied,
      search: search.query,
      listMode: useUIStore.getState().viewMode === 'list',
      clearSearch: search.clear,
      select: (id) => canvas.setSelection(new Set([id])),
      reveal: search.requestReveal,
      centre: (snippet) => {
        const view = canvas.canvasViewSize ?? { width: window.innerWidth, height: window.innerHeight };
        canvas.setViewport(viewportCentredOn(snippet, view, canvas.viewport.zoom));
      },
      notify: (text, tone) => usePlacementNoticeStore.getState().show(text, tone),
    });
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete(FOCUS_PARAM);
        return next;
      },
      { replace: true },
    );
  }, [snippetId, routeCanvasId, loadedCanvasId, setParams]);
}
