import type { GridData, GridMode, Snippet, SnippetType } from '@/types';

export type MergeMode = 'vertical' | 'horizontal';

/** At least one snippet; the first one supplies type, grid mode, position, and group. */
export type NonEmptySnippets = readonly [Snippet, ...Snippet[]];

/** The pure outcome of a merge: everything the store needs to create the replacement snippet. */
export interface MergedSnippet {
  raw_text: string;
  grid_data: GridData;
  snippet_type: SnippetType;
  grid_mode: GridMode;
  beats_per_line: number | null;
  group_id: string | null;
  position_x: number;
  position_y: number;
  /** Union of the originals' tag ids, in first-seen order. */
  tag_ids: string[];
  /** The originals in merge order; deleted once the merged snippet exists. */
  source_ids: string[];
}

export type MergeErrorKind =
  | 'no_canvas'
  | 'too_few_selected'
  | 'create_failed'
  | 'tag_failed'
  | 'delete_failed';

/** `error`: nothing changed. `warning`: the merged snippet exists but a follow-up step failed. */
export type MergeErrorLevel = 'error' | 'warning';

const LEVELS: Record<MergeErrorKind, MergeErrorLevel> = {
  no_canvas: 'error',
  too_few_selected: 'error',
  create_failed: 'error',
  tag_failed: 'warning',
  delete_failed: 'warning',
};

export class MergeError extends Error {
  readonly kind: MergeErrorKind;
  readonly level: MergeErrorLevel;
  readonly status: number | null;
  /** The snippet or tag ids the failed step was acting on. */
  readonly ids: readonly string[];

  constructor(kind: MergeErrorKind, message: string, status: number | null = null, ids: readonly string[] = []) {
    super(message);
    this.name = 'MergeError';
    this.kind = kind;
    this.level = LEVELS[kind];
    this.status = status;
    this.ids = ids;
  }
}

export type MergeResult =
  | { ok: true; snippet: Snippet; warnings: MergeError[] }
  | { ok: false; error: MergeError };
