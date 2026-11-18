import { ScoreSaveError } from '@/lib/score/saveFlow';
import type { SnippetUpdateBody } from '@/api/placements';
import type { Score } from '@/types/score';

export type SaveDraftResult = { ok: true } | { ok: false; message: string };

/**
 * Save a chosen draft on the snippet and show its staff (`score_view: true`), through the store's
 * update (which runs the local score save flow first). Never rejects.
 */
export async function saveDraftedScore(
  snippetId: string,
  score: Score,
  update: (id: string, body: SnippetUpdateBody) => Promise<void>,
): Promise<SaveDraftResult> {
  try {
    await update(snippetId, { score, score_view: true });
    return { ok: true };
  } catch (err) {
    if (err instanceof ScoreSaveError) {
      console.warn('score draft: save refused', { snippetId, reason: err.invalid.reason });
      return { ok: false, message: 'The draft does not fit the snippet any more; draft it again' };
    }
    const message = err instanceof Error ? err.message : String(err);
    console.warn('score draft: save failed', { snippetId, error: message });
    return { ok: false, message: `Saving the score failed: ${message}` };
  }
}
