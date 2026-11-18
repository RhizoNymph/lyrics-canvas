/** Score export file naming and result wording (contract §5). */
import { SCORE_EXPORT_EXTENSIONS } from '@/types/scoreTools';
import type { ScoreApiError, ScoreExportFormat } from '@/types/scoreTools';
import type { Score } from '@/types/score';
import { describeScoreApiError } from './errors';

/** `snippet-<first 8 of the id><ext>`, for when the server's Content-Disposition is unreadable. */
export function exportFallbackFilename(snippetId: string, format: ScoreExportFormat): string {
  return `snippet-${snippetId.slice(0, 8)}${SCORE_EXPORT_EXTENSIONS[format]}`;
}

/**
 * Whether a snippet has a score (the export entries show only then). Takes the optional field so it
 * works on any snippet-like object, before and after the store carries `score`.
 */
export function hasScore(snippet: { readonly score?: Score | null }): boolean {
  return typeof snippet.score === 'object' && snippet.score !== null;
}

export type ExportOutcome = { ok: true; filename: string } | { ok: false; error: ScoreApiError };

export interface ExportNotice {
  text: string;
  tone: 'info' | 'error';
}

/** The toast line after an export. */
export function exportNotice(outcome: ExportOutcome): ExportNotice {
  if (outcome.ok) return { text: `Saved ${outcome.filename}`, tone: 'info' };
  const d = describeScoreApiError(outcome.error);
  return { text: `Export failed: ${d.title}`, tone: 'error' };
}
