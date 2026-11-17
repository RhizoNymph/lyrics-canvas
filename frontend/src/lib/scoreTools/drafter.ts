/**
 * Where a draft comes from: a recording draft always from the server (alignment and tempo estimation are
 * backend only); a grid draft locally when the caller passes a drafter over its live grid (the editor's
 * `draftFromGrid` port, so unsaved edits are drafted too), else from the server over the stored grid.
 */
import type { ScoreApi } from '@/api/score';
import type { ScoreApiError, GridDraftRequest, ScoreDraftRequest, ScoreDraftResponse } from '@/types/scoreTools';
import type { Score } from '@/types/score';

export type LocalDraftResult = { ok: true; score: Score } | { ok: false; error: ScoreApiError };

/** A grid draft computed on the client (it must not throw: failures are returned). */
export type LocalGridDrafter = (request: GridDraftRequest) => LocalDraftResult;

export interface DraftDeps {
  draft: ScoreApi['draft'];
  local?: LocalGridDrafter;
}

export async function runDraft(
  snippetId: string,
  request: ScoreDraftRequest,
  deps: DraftDeps,
  signal?: AbortSignal,
): Promise<ScoreDraftResponse> {
  if (request.source === 'grid' && deps.local !== undefined) {
    const result = deps.local(request);
    if (!result.ok) throw result.error;
    // A grid draft gives every syllable a note and has no take words.
    return { score: result.score, bpm_estimated: null, unplaced: 0, unmatched_words: 0 };
  }
  return deps.draft(snippetId, request, signal);
}
