/**
 * The content save flow of a snippet with a score, run locally so the optimistic state equals what the
 * server stores (contract §3, spec section 13):
 *
 *  1. a sent `score: null` removes the score (and `score_view` turns off);
 *  2. a sent score is validated against the sent (or current) grid;
 *  3. no score sent but the grid changed: a rest-only edit moves the notes (applyGridRestEdit), any other
 *     change reconciles the stored score;
 *  4. finally the rest link rewrites the grid and the changed lines of `raw_text` are spliced.
 *
 * The planned body sends the final `score`, `grid_data` and `raw_text`, so the server's own pass is the
 * identity (it is idempotent) and the two never disagree.
 */
import type { SnippetUpdateBody } from '@/api/placements';
import type { GridData, Snippet } from '@/types';
import type { Score, SnippetScoreFields } from '@/types/score';
import { ScoreInvalid, ScoreTextMismatch } from './errors';
import { jsonEqual } from './json';
import { applyGridRestEdit, applyRestLink, isRestOnlyEdit } from './restLink';
import { reconcile } from './reconcile';
import { changedLineTexts, spliceLineTexts } from './text';
import { checkScore } from './validate';

/** The content fields the flow reads and writes. */
export interface ScoreContent {
  raw_text: string;
  grid_data: GridData | null;
  score: Score | null;
  score_view: boolean;
}

/** A snippet's score content; a snippet from a server without musical mode has no score. */
export function scoreContentOf(snippet: Pick<Snippet, 'raw_text' | 'grid_data'> & Partial<SnippetScoreFields>): ScoreContent {
  return {
    raw_text: snippet.raw_text,
    grid_data: snippet.grid_data,
    score: snippet.score ?? null,
    score_view: snippet.score_view === true && snippet.score != null,
  };
}

export interface ContentSavePlan {
  /** What to PUT. The same object as the input body when the score is not involved. */
  readonly body: SnippetUpdateBody;
  /** The fields to apply locally right away (only the ones that change). */
  readonly local: Partial<ScoreContent>;
}

/** A save the server would refuse (`invalid_score`); nothing should be sent. */
export class ScoreSaveError extends Error {
  readonly kind = 'score_save' as const;
  constructor(readonly invalid: ScoreInvalid) {
    super(`the score does not fit this edit: ${invalid.message}`);
    this.name = 'ScoreSaveError';
  }
}

function linkText(rawText: string, grid: GridData | null, linked: GridData | null): string {
  try {
    return spliceLineTexts(rawText, changedLineTexts(grid, linked));
  } catch (error) {
    if (!(error instanceof ScoreTextMismatch)) throw error;
    console.warn('score rest link: text lines do not match the grid; keeping the text', { index: error.index, available: error.available });
    return rawText;
  }
}

function guarded<T>(fn: () => T): T {
  try {
    return fn();
  } catch (error) {
    if (error instanceof ScoreInvalid) throw new ScoreSaveError(error);
    throw error;
  }
}

/** Plan a `PUT /api/snippets/{id}` against the current content. Throws `ScoreSaveError`. */
export function planContentSave(current: ScoreContent, body: SnippetUpdateBody): ContentSavePlan {
  const currentScore = current.score ?? null;
  const sendsScore = body.score !== undefined;
  const sendsGrid = body.grid_data !== undefined;
  if (!sendsScore && !sendsGrid && body.score_view === undefined) return { body, local: {} };
  if (body.score === null) return { body: { ...body, score: null, score_view: false }, local: { score: null, score_view: false } };
  if (!sendsScore && currentScore === null) {
    // No score: grid edits pass through; a view flag is forced off like the server does.
    if (body.score_view === undefined) return { body, local: {} };
    return { body: { ...body, score_view: false }, local: { score_view: false } };
  }
  if (!sendsScore && !sendsGrid) return { body, local: { score_view: body.score_view } };

  const oldGrid = current.grid_data ?? null;
  const grid: GridData | null = sendsGrid ? (body.grid_data ?? null) : oldGrid;
  const rawText = body.raw_text ?? current.raw_text;
  let score: Score;
  let linkedGrid: GridData | null;
  if (sendsScore && body.score) {
    const sent = body.score;
    score = guarded(() => checkScore(sent, grid));
    linkedGrid = guarded(() => applyRestLink(score, grid));
  } else if (currentScore !== null && sendsGrid && !jsonEqual(oldGrid, grid)) {
    if (isRestOnlyEdit(oldGrid, grid)) {
      const moved = guarded(() => applyGridRestEdit(currentScore, oldGrid, grid));
      score = moved.score;
      linkedGrid = moved.grid;
    } else {
      score = guarded(() => reconcile(currentScore, oldGrid, grid));
      linkedGrid = guarded(() => applyRestLink(score, grid));
    }
  } else if (currentScore !== null) {
    score = currentScore;
    linkedGrid = guarded(() => applyRestLink(currentScore, grid));
  } else {
    throw new Error('unreachable: a score is sent or stored here');
  }
  const text = linkText(rawText, grid, linkedGrid);
  const local: Partial<ScoreContent> = { score, grid_data: linkedGrid, raw_text: text };
  const next: SnippetUpdateBody = { ...body, score, grid_data: linkedGrid, raw_text: text };
  if (body.score_view !== undefined) local.score_view = body.score_view;
  return { body: next, local };
}
