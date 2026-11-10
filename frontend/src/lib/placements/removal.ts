import { describePlacementError } from './errors';
import type { PlacementError } from './errors';

/** What a remove / delete-everywhere of several snippets did (never thrown). */
export interface RemovalSummary {
  /** Snippets that left this canvas (including ones the server had already removed). */
  removed: string[];
  /** Of those, the ones that no longer exist anywhere. */
  deletedForGood: string[];
  /** Snippets still here because their request failed. */
  failures: Array<{ id: string; error: PlacementError }>;
}

export type RemovalAction = 'remove' | 'delete_everywhere';

/** The error notice after a removal with failures, or null when all went through. */
export function removalFailureNotice(summary: RemovalSummary, action: RemovalAction): string | null {
  const n = summary.failures.length;
  if (n === 0) return null;
  const what = `${n} snippet${n === 1 ? '' : 's'}`;
  const head = action === 'remove' ? `Could not remove ${what} from this canvas` : `Could not delete ${what}`;
  return `${head}: ${describePlacementError(summary.failures[0].error)}`;
}
