import { useCallback } from 'react';
import { useCanvasStore } from '@/stores/canvasStore';
import { usePlacementNoticeStore } from '@/stores/placementNoticeStore';
import { useScoreDraftStore } from '@/stores/scoreDraftStore';
import { localGridDrafter } from '@/lib/scoreTools/drafter';
import type { Score } from '@/types/score';
import ScoreDraftDialog from './ScoreDraftDialog';
import { saveDraftedScore } from './saveDraftedScore';

/**
 * The canvas page's "Make score" dialog for the snippet in `scoreDraftStore` (opened from the context
 * menu): grid drafts run locally over the stored grid, and the chosen draft is saved right away with the
 * staff view on. Mounted once on the canvas page.
 */
export default function ScoreDraftHost() {
  const target = useScoreDraftStore((s) => s.target);
  const close = useScoreDraftStore((s) => s.close);
  const snippet = useCanvasStore((s) => (target === null ? undefined : s.snippets.get(target.snippetId)));
  const showNotice = usePlacementNoticeStore((s) => s.show);

  const onDraft = useCallback(
    (score: Score) => {
      if (snippet === undefined) return;
      void saveDraftedScore(snippet.id, score, useCanvasStore.getState().updateSnippet).then((result) => {
        if (!result.ok) showNotice(result.message, 'error');
      });
    },
    [snippet, showNotice],
  );

  if (target === null || snippet === undefined) return null;
  return (
    <ScoreDraftDialog
      key={snippet.id}
      snippet={snippet}
      onDraft={onDraft}
      onClose={close}
      draftGrid={localGridDrafter(snippet.grid_data)}
    />
  );
}
