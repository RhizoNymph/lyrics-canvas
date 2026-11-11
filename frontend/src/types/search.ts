/** `GET /api/search` (rest-search contract §3b): the caller's canvases and snippets matching a query. */
import type { CanvasRef, SnippetType } from './index';

export interface SearchCanvasHit {
  id: string;
  title: string;
  updated_at: string;
}

export interface SearchSnippetHit {
  snippet_id: string;
  snippet_type: SnippetType;
  content_updated_at: string;
  /** The first display line containing the first term (about 160 characters), else the first display line. */
  excerpt: string;
  /** Every canvas holding the snippet, sorted by lower(title), then id. */
  canvases: CanvasRef[];
}

export interface GlobalSearchResponse {
  /** Canvases whose title matches every term, last updated first. */
  canvases: SearchCanvasHit[];
  /** Snippets whose display text matches every term, last content edit first. */
  snippets: SearchSnippetHit[];
}
