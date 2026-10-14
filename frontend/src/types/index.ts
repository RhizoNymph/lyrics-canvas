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

export interface Tag {
  id: string;
  canvas_id: string;
  name: string;
  color: string;
}

export interface Snippet {
  id: string;
  canvas_id: string;
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
  tags: Tag[];
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
  tags: Tag[];
}

// ---- Merge (POST /api/canvases/{id}/merge) ----
export type MergeMode = 'vertical' | 'horizontal';

export interface MergeResponse {
  /** The merged snippet, created at the first block's position with the union of the tags. */
  snippet: Snippet;
  /** The originals the server deleted, in merge order. */
  deleted_ids: string[];
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
