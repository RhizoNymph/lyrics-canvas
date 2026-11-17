/**
 * Score draft and export endpoints (round-4 contract §5). Built over an injected axios instance so tests
 * drive it with a mock adapter; `client.ts` binds it to the app instance. Every function rejects with a
 * `ScoreApiError` only.
 */
import axios from 'axios';
import type { AxiosInstance } from 'axios';
import { filenameFromContentDisposition } from '@/lib/voice/dataset';
import { exportFallbackFilename } from '@/lib/scoreTools/exportFile';
import { toScoreApiError } from '@/lib/scoreTools/errors';
import { ScoreApiError } from '@/types/scoreTools';
import type { ScoreDraftRequest, ScoreDraftResponse, ScoreExportDownload, ScoreExportFormat } from '@/types/scoreTools';

export interface ScoreApi {
  /** A draft score for the snippet (not saved: the caller saves it with the snippet PUT). */
  draft(snippetId: string, request: ScoreDraftRequest, signal?: AbortSignal): Promise<ScoreDraftResponse>;
  /** The snippet's score as a MIDI or MusicXML file, named by the server's Content-Disposition. */
  exportScore(snippetId: string, format: ScoreExportFormat, signal?: AbortSignal): Promise<ScoreExportDownload>;
}

/** With `responseType: 'blob'` an error body arrives as a Blob; decode a JSON one so its code is read. */
async function decodeBlobErrorBody(err: unknown): Promise<void> {
  if (!axios.isAxiosError(err) || !err.response || !(err.response.data instanceof Blob)) return;
  let text: string;
  try {
    text = await err.response.data.text();
  } catch (readError) {
    console.warn('score api: error body unreadable', { status: err.response.status, error: readError });
    return;
  }
  try {
    err.response.data = JSON.parse(text);
  } catch (parseError) {
    if (!(parseError instanceof SyntaxError)) throw parseError;
    err.response.data = text; // not JSON (a proxy page): the status decides
  }
}

function isDraftResponse(data: unknown): data is ScoreDraftResponse {
  if (typeof data !== 'object' || data === null) return false;
  const d = data as Partial<Record<keyof ScoreDraftResponse, unknown>>;
  return (
    typeof d.score === 'object' &&
    d.score !== null &&
    (d.bpm_estimated === null || typeof d.bpm_estimated === 'number') &&
    typeof d.unplaced === 'number' &&
    typeof d.unmatched_words === 'number'
  );
}

export function createScoreApi(http: AxiosInstance): ScoreApi {
  return {
    draft: async (snippetId, request, signal) => {
      let data: unknown;
      try {
        data = (await http.post<unknown>(`/api/snippets/${encodeURIComponent(snippetId)}/score/draft`, request, { signal })).data;
      } catch (err) {
        throw toScoreApiError(err, 'draft');
      }
      if (!isDraftResponse(data)) {
        console.warn('score api: draft response malformed', { snippetId, source: request.source });
        throw new ScoreApiError('http', 'draft', 'The server sent a malformed draft');
      }
      return data;
    },
    exportScore: async (snippetId, format, signal) => {
      try {
        const res = await http.get<Blob>(`/api/snippets/${encodeURIComponent(snippetId)}/score/export`, {
          params: { format },
          responseType: 'blob',
          signal,
        });
        const header: unknown = res.headers['content-disposition'];
        const filename =
          filenameFromContentDisposition(typeof header === 'string' ? header : null) ?? exportFallbackFilename(snippetId, format);
        return { blob: res.data, filename };
      } catch (err) {
        await decodeBlobErrorBody(err);
        throw toScoreApiError(err, 'export');
      }
    },
  };
}
