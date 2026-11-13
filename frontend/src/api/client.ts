import axios from 'axios';
import { apiHealthChannel } from '@/lib/health/channel';
import { installHealthInterceptors } from '@/lib/health/interceptors';
import type { CanvasDetail, GridData, GridMode, MergeMode, MergeResponse, Snippet, SnippetType, Tag, TagPreset } from '@/types';
import type { DerivedFrom, RhymeSuggestRequest, RhymeSuggestResponse } from '@/types/rhyme';
import { createPlacementApi } from './placements';
import { createVoiceApi } from './voice';
import { createWordBankApi } from './wordBank';
import { createSearchApi } from './search';
import { createRhymePatternApi } from './rhymePattern';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '',
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  }
);

// Report every call's outcome to the health monitor; responses and errors pass through unchanged.
installHealthInterceptors(api, apiHealthChannel.publish, () => navigator.onLine);

// ---- Auth ----
export const authApi = {
  register: (email: string, password: string, display_name: string) =>
    api.post('/api/auth/register', { email, password, display_name }),
  login: (email: string, password: string) =>
    api.post('/api/auth/login', { email, password }),
  googleLogin: (credential: string) =>
    api.post('/api/auth/google', { credential }),
  me: () => api.get('/api/auth/me'),
};

// ---- Canvases ----
// List, create and rename live in `placementApi` (typed, PlacementError rejections).
export const canvasApi = {
  get: (id: string) => api.get<CanvasDetail>(`/api/canvases/${id}`),
  /** Generic update (e.g. `viewport_state`); renames go through `placementApi.renameCanvas`. */
  update: (id: string, data: Record<string, unknown>) => api.put(`/api/canvases/${id}`, data),
  delete: (id: string) => api.delete(`/api/canvases/${id}`),
  share: (id: string) => api.post(`/api/canvases/${id}/share`),
  fork: (id: string) => api.post(`/api/canvases/${id}/fork`),
  getShared: (token: string) => api.get(`/api/canvases/shared/${token}`),
  /** Replace two or more snippets with one merged snippet, server-side and all or nothing. */
  merge: (canvasId: string, snippetIds: string[], mode: MergeMode) =>
    api.post<MergeResponse>(`/api/canvases/${canvasId}/merge`, { snippet_ids: snippetIds, mode }),
};

// ---- Snippets ----
export interface SnippetCreateBody {
  position_x: number;
  position_y: number;
  snippet_type?: SnippetType;
  raw_text?: string;
  grid_mode?: GridMode;
  beats_per_line?: number | null;
  /** Stored as-is (no re-syllabification). */
  grid_data?: GridData;
  group_id?: string | null;
  /** Set only by rhyme-view promotion. */
  derived_from?: DerivedFrom;
}

export const snippetApi = {
  create: (canvasId: string, data: SnippetCreateBody) =>
    api.post<Snippet>(`/api/canvases/${canvasId}/snippets`, data),
  // Update, batch-position (both need the open canvas's id), remove-from-canvas, delete-everywhere and
  // transfer live in `placementApi`.
};

// ---- Canvas rename and snippet placements (see api/placements.ts) ----
export const placementApi = createPlacementApi(api);

// ---- Groups ----
export const groupApi = {
  create: (canvasId: string, name: string) =>
    api.post(`/api/canvases/${canvasId}/groups`, { name }),
  update: (id: string, data: Record<string, unknown>) =>
    api.put(`/api/groups/${id}`, data),
  delete: (id: string) => api.delete(`/api/groups/${id}`),
};

// ---- Tags (user-wide) ----
export interface TagUpdateBody {
  name?: string;
  color?: string;
}

export const tagApi = {
  /** Every tag of the user, sorted by lower(name). */
  list: () => api.get<Tag[]>('/api/tags'),
  /** The server defaults the color to #6366f1 when omitted. */
  create: (name: string, color?: string) =>
    api.post<Tag>('/api/tags', color === undefined ? { name } : { name, color }),
  /** A rename also rewrites the user's presets server-side. */
  update: (id: string, data: TagUpdateBody) => api.put<Tag>(`/api/tags/${encodeURIComponent(id)}`, data),
  delete: (id: string) => api.delete<void>(`/api/tags/${encodeURIComponent(id)}`),
  addToSnippet: (snippetId: string, tagId: string) =>
    api.post(`/api/snippets/${encodeURIComponent(snippetId)}/tags`, null, { params: { tag_id: tagId } }),
  removeFromSnippet: (snippetId: string, tagId: string) =>
    api.delete<void>(`/api/snippets/${encodeURIComponent(snippetId)}/tags/${encodeURIComponent(tagId)}`),
};

// ---- Tag presets (saved filter expressions, user-wide) ----
export interface TagPresetUpdateBody {
  name?: string;
  expression?: string;
}

export const tagPresetApi = {
  list: () => api.get<TagPreset[]>('/api/tag-presets'),
  create: (name: string, expression: string) => api.post<TagPreset>('/api/tag-presets', { name, expression }),
  update: (id: string, data: TagPresetUpdateBody) =>
    api.put<TagPreset>(`/api/tag-presets/${encodeURIComponent(id)}`, data),
  delete: (id: string) => api.delete<void>(`/api/tag-presets/${encodeURIComponent(id)}`),
};

// ---- NLP ----
export const nlpApi = {
  syllabify: (text: string) => api.post('/api/nlp/syllabify', { text }),
  similar: (snippetId: string, topK = 10) =>
    api.get(`/api/snippets/${snippetId}/similar`, { params: { top_k: topK } }),
  tsne: (canvasId: string, nComponents = 2) =>
    api.post(`/api/canvases/${canvasId}/tsne`, { n_components: nComponents }),
  rhymes: (word: string) => api.get('/api/nlp/rhymes', { params: { word } }),
  rhymeSuggest: (req: RhymeSuggestRequest) =>
    api.post<RhymeSuggestResponse>('/api/nlp/rhyme-suggest', req),
};

// ---- Rhyme patterns across lines (see api/rhymePattern.ts) ----
export const rhymePatternApi = createRhymePatternApi(api);

// ---- Export ----
export const exportApi = {
  export: (canvasId: string, format: string) =>
    api.post(`/api/canvases/${canvasId}/export`, { format }, { responseType: 'blob' }),
};

// ---- Voice takes and recordings (see api/voice.ts) ----
export const voiceApi = createVoiceApi(api);

// ---- Search across the user's canvases (see api/search.ts) ----
export const searchApi = createSearchApi(api);

// ---- Word bank (see api/wordBank.ts) ----
export const wordBankApi = createWordBankApi(api);

export default api;
