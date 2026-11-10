/**
 * Canvas rename and snippet placement endpoints (snippet placements contract §2). Built over an injected axios
 * instance so tests can drive it with a mock adapter; `client.ts` binds it to the app instance. Every
 * function rejects with a `PlacementError` only.
 */
import type { AxiosInstance } from 'axios';
import { toPlacementError } from '@/lib/placements/errors';
import type { PlacementOperation } from '@/lib/placements/errors';
import type {
  Canvas,
  PositionUpdate,
  RemovePlacementResponse,
  Snippet,
  TransferRequest,
  TransferResponse,
} from '@/types';

/** Fields `PUT /api/snippets/{id}` takes: content (shared) and layout (the placement on `canvas_id`). */
export type SnippetUpdateBody = Partial<
  Pick<
    Snippet,
    | 'raw_text'
    | 'snippet_type'
    | 'grid_mode'
    | 'beats_per_line'
    | 'grid_data'
    | 'position_x'
    | 'position_y'
    | 'width'
    | 'height'
    | 'z_index'
    | 'group_id'
  >
>;

export interface PlacementApi {
  listCanvases(): Promise<Canvas[]>;
  /** An empty title becomes "Untitled Canvas" server-side. */
  createCanvas(title: string): Promise<Canvas>;
  renameCanvas(canvasId: string, title: string): Promise<Canvas>;
  /** `canvas_id` is required: layout fields go to the placement on it, and the response is in its context. */
  updateSnippet(snippetId: string, canvasId: string, data: SnippetUpdateBody): Promise<Snippet>;
  batchPosition(canvasId: string, updates: PositionUpdate[]): Promise<void>;
  /** Remove from this canvas; deletes the snippet when it was its last placement. */
  removeFromCanvas(canvasId: string, snippetId: string): Promise<RemovePlacementResponse>;
  /** Delete the snippet, every placement and its recordings. */
  deleteEverywhere(snippetId: string): Promise<void>;
  transfer(sourceCanvasId: string, request: TransferRequest): Promise<TransferResponse>;
}

async function wrap<T>(operation: PlacementOperation, call: () => Promise<{ data: T }>): Promise<T> {
  try {
    return (await call()).data;
  } catch (err) {
    throw toPlacementError(err, operation);
  }
}

const enc = encodeURIComponent;

export function createPlacementApi(http: AxiosInstance): PlacementApi {
  return {
    listCanvases: () => wrap('list_canvases', () => http.get<Canvas[]>('/api/canvases')),
    createCanvas: (title) => wrap('create_canvas', () => http.post<Canvas>('/api/canvases', { title })),
    renameCanvas: (canvasId, title) =>
      wrap('rename_canvas', () => http.put<Canvas>(`/api/canvases/${enc(canvasId)}`, { title })),
    updateSnippet: (snippetId, canvasId, data) =>
      wrap('update_snippet', () =>
        http.put<Snippet>(`/api/snippets/${enc(snippetId)}`, data, { params: { canvas_id: canvasId } }),
      ),
    batchPosition: async (canvasId, updates) => {
      await wrap('save_positions', () =>
        http.patch<unknown>('/api/snippets/batch-position', { canvas_id: canvasId, updates }),
      );
    },
    removeFromCanvas: (canvasId, snippetId) =>
      wrap('remove_from_canvas', () =>
        http.delete<RemovePlacementResponse>(`/api/canvases/${enc(canvasId)}/snippets/${enc(snippetId)}`),
      ),
    deleteEverywhere: async (snippetId) => {
      await wrap('delete_everywhere', () => http.delete<unknown>(`/api/snippets/${enc(snippetId)}`));
    },
    transfer: (sourceCanvasId, request) =>
      wrap('transfer', () =>
        http.post<TransferResponse>(`/api/canvases/${enc(sourceCanvasId)}/snippets/transfer`, request),
      ),
  };
}
