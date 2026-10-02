import type { MergeMode, Snippet } from '@/types';

export type { MergeMode };

/**
 * Why a merge did not happen. The server merges in one transaction, so a failed request
 * means nothing changed, locally or remotely.
 */
export type MergeErrorKind = 'no_canvas' | 'too_few_selected' | 'merge_failed';

export class MergeError extends Error {
  readonly kind: MergeErrorKind;
  /** HTTP status of a failed merge request; null for local guards and network errors. */
  readonly status: number | null;
  /** The snippet ids the merge was acting on. */
  readonly ids: readonly string[];

  constructor(kind: MergeErrorKind, message: string, status: number | null = null, ids: readonly string[] = []) {
    super(message);
    this.name = 'MergeError';
    this.kind = kind;
    this.status = status;
    this.ids = ids;
  }
}

export type MergeResult = { ok: true; snippet: Snippet } | { ok: false; error: MergeError };
