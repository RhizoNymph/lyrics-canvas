/**
 * Where a draft comes from: a recording draft always from the server (alignment and tempo estimation are
 * backend only); a grid draft locally when the caller passes a drafter over its live grid (the editor's
 * `draftFromGrid` port, so unsaved edits are drafted too), else from the server over the stored grid.
 */
import type { ScoreApi } from '@/api/score';
import { ScoreInvalid, draftFromGrid } from '@/lib/score';
import { ScoreApiError } from '@/types/scoreTools';
import type { GridDraftRequest, ScoreDraftRequest, ScoreDraftResponse } from '@/types/scoreTools';
import type { GridData } from '@/types';
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

/**
 * A local grid drafter over `grid` (the editor's live grid): the `draftFromGrid` port of
 * feat/web-score-editor, its `ScoreInvalid` returned as an `invalid_score` ScoreApiError.
 */
export function localGridDrafter(grid: GridData | null): LocalGridDrafter {
  return (request) => {
    try {
      return { ok: true, score: draftFromGrid(grid, request.meter, request.bpm, request.note) };
    } catch (err) {
      if (!(err instanceof ScoreInvalid)) throw err;
      return {
        ok: false,
        error: new ScoreApiError('invalid_score', 'draft', err.message, null, {
          reason: err.reason,
          line: err.line ?? null,
          note: err.note ?? null,
        }),
      };
    }
  };
}
