// ---- Beat Grid ----
export interface GridCell {
  syllable?: string;
  stress?: number; // 0=unstressed, 1=primary, 2=secondary
  word_index?: number;
  rest?: boolean;
  /** Surface word this syllable belongs to (as typed, incl. punctuation); lets raw_text be rebuilt after rearranging. */
  word?: string;
}

export interface GridLine {
  cells: GridCell[];
  syllable_count: number;
  raw_text: string;
}

export interface GridData {
  lines: GridLine[];
}

// ---- Core Models ----
export type SnippetType = 'verse' | 'chorus' | 'bridge' | 'hook' | 'freestyle';
export type GridMode = 'auto' | 'fixed';

/** A user-wide tag (`GET /api/tags`); shared by every canvas of its owner. */
export interface Tag {
  id: string;
  name: string;
  /** `#rrggbb`. */
  color: string;
  /** Snippets (across all canvases) carrying this tag. */
  snippet_count: number;
  /** Word bank words carrying this tag. */
  word_count: number;
  created_at: string;
}

/** A tag as embedded in a snippet response. */
export interface SnippetTag {
  id: string;
  name: string;
  color: string;
}

/** A saved filter expression (`GET /api/tag-presets`); `expression` is stored normalized and never empty. */
export interface TagPreset {
  id: string;
  name: string;
  expression: string;
  created_at: string;
  updated_at: string;
}

/** A canvas as embedded in a snippet response (`Snippet.canvases`). */
export interface CanvasRef {
  id: string;
  title: string;
}

/**
 * A snippet in the context of one canvas: content fields are shared by every canvas holding it; `canvas_id`,
 * `placement_id` and the layout fields (position, size, `z_index`, `group_id`) come from its placement on
 * `canvas_id`.
 */
export interface Snippet {
  id: string;
  /** The canvas this response is in the context of (the open canvas). */
  canvas_id: string;
  /** The placement on `canvas_id`. */
  placement_id: string;
  /** Every canvas holding this snippet, the current one included; sorted by lower(title), then id. */
  canvases: CanvasRef[];
  snippet_type: SnippetType;
  grid_mode: GridMode;
  beats_per_line: number | null;
  grid_data: GridData | null;
  raw_text: string;
  position_x: number;
  position_y: number;
  width: number;
  height: number;
  z_index: number;
  group_id: string | null;
  tags: SnippetTag[];
  created_at: string;
  updated_at: string;
  /** ISO-8601; bumped by the server on content changes (text, type, grid) only, never on moves. */
  content_updated_at: string;
}

export interface Group {
  id: string;
  canvas_id: string;
  name: string;
  position_x: number;
  position_y: number;
  snippet_ids: string[];
  created_at: string;
}

export interface Canvas {
  id: string;
  owner_id: string;
  title: string;
  is_public: boolean;
  share_token: string | null;
  viewport_state: { x: number; y: number; zoom: number } | null;
  created_at: string;
  updated_at: string;
}

export interface CanvasDetail extends Canvas {
  snippets: Snippet[];
  groups: Group[];
}

// ---- Merge (POST /api/canvases/{id}/merge) ----
export type MergeMode = 'vertical' | 'horizontal';

export interface MergeResponse {
  /** The merged snippet, created at the first block's position with the union of the tags. */
  snippet: Snippet;
  /** The originals the server deleted, in merge order. */
  deleted_ids: string[];
}

// ---- Snippet placements (contract §2) ----
export type TransferMode = 'move' | 'show';

/** `POST /api/canvases/{cid}/snippets/transfer`; 1..500 ids, all placed on `cid`. */
export interface TransferRequest {
  snippet_ids: string[];
  target_canvas_id: string;
  mode: TransferMode;
}

export interface TransferResponse {
  target_canvas_id: string;
  /** Snippets that got a new placement on the target. */
  placed: string[];
  /** Snippets already on the target (their target placement is untouched). */
  already_on_target: string[];
  /** True in `move` mode: every requested snippet left the source canvas. */
  removed_from_source: boolean;
}

/** `DELETE /api/canvases/{cid}/snippets/{sid}`. */
export interface RemovePlacementResponse {
  /** True when that was the snippet's last placement and the snippet itself is gone. */
  snippet_deleted: boolean;
}

/** One entry of `PATCH /api/snippets/batch-position`. */
export interface PositionUpdate {
  id: string;
  position_x: number;
  position_y: number;
}

export interface User {
  id: string;
  email: string;
  display_name: string;
  oauth_provider: string | null;
  created_at: string;
}

// ---- NLP ----
export interface SimilarityResult {
  snippet_id: string;
  raw_text: string;
  score: number;
  snippet_type: string;
}

export interface RhymeResult {
  word: string;
  rhyme_type: string;
  source: string;
  snippet_id?: string;
  snippet_preview?: string;
}

export interface TSNEPosition {
  snippet_id: string;
  x: number;
  y: number;
  z?: number;
}

// ---- Snippet type colors ----
export const SNIPPET_TYPE_COLORS: Record<SnippetType, string> = {
  verse: '#3b82f6',
  chorus: '#f59e0b',
  bridge: '#8b5cf6',
  hook: '#ef4444',
  freestyle: '#6b7280',
};
