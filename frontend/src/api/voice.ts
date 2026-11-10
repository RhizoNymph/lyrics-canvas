/**
 * Voice-take and recording endpoints (contract §2). Built over an injected axios instance so tests can
 * drive it with a mock adapter; `client.ts` binds it to the authenticated app instance. Every function
 * rejects with a `VoiceError` only.
 */
import axios from 'axios';
import type { AxiosInstance } from 'axios';
import { fallbackExportFilename, filenameFromContentDisposition } from '@/lib/voice/dataset';
import { toVoiceError } from '@/lib/voice/errors';
import type { StatusKinds } from '@/lib/voice/errors';
import { uploadFileName } from '@/lib/voice/support';
import type {
  DatasetDownload,
  RecordingResponse,
  Take,
  VoiceDatasetStats,
  VoiceTakeMeta,
  VoiceTakeResponse,
  VoiceTakeTarget,
} from '@/lib/voice/types';

/** `vocabularyHints` undefined omits the field (the server then defaults to true). */
export function buildTakeMeta(take: Take, target: VoiceTakeTarget, vocabularyHints?: boolean): VoiceTakeMeta {
  const meta: VoiceTakeMeta = { breaks_ms: [...take.breaksMs], duration_ms: take.durationMs, target };
  if (vocabularyHints !== undefined) meta.vocabulary_hints = vocabularyHints;
  return meta;
}

export interface TakeOptions {
  /** Use the word bank as Whisper's initial prompt (the recorder's switch). */
  vocabularyHints: boolean;
}

export interface ExportOptions {
  includeUnverified: boolean;
}

export function buildTakeForm(take: Take, target: VoiceTakeTarget, vocabularyHints?: boolean): FormData {
  const form = new FormData();
  // The part's type is the recorder's mime (parameters included) so the server can validate it.
  const audio = take.blob.type === take.mimeType ? take.blob : new Blob([take.blob], { type: take.mimeType });
  form.append('audio', audio, uploadFileName(take.mimeType));
  form.append('meta', JSON.stringify(buildTakeMeta(take, target, vocabularyHints)));
  return form;
}

async function wrap<T>(call: () => Promise<{ data: T }>): Promise<T> {
  try {
    return (await call()).data;
  } catch (err) {
    throw toVoiceError(err);
  }
}

/** With `responseType: 'blob'` an error body arrives as a Blob too; decode a JSON one so its code is read. */
async function decodeBlobErrorBody(err: unknown): Promise<void> {
  if (!axios.isAxiosError(err) || !err.response || !(err.response.data instanceof Blob)) return;
  let text: string;
  try {
    text = await err.response.data.text();
  } catch (readError) {
    console.warn('voice api: error body unreadable', { status: err.response.status, error: readError });
    return;
  }
  try {
    err.response.data = JSON.parse(text);
  } catch (parseError) {
    if (!(parseError instanceof SyntaxError)) throw parseError;
    err.response.data = text; // not JSON (an nginx page): the status decides
  }
}

const EXPORT_STATUS_KINDS: StatusKinds = { 413: 'export_too_large', 404: 'not_found' };

export interface VoiceApi {
  /** `options` omitted leaves `vocabulary_hints` to the server default; the recorder always passes it. */
  createTake(
    canvasId: string,
    take: Take,
    target: VoiceTakeTarget,
    signal?: AbortSignal,
    options?: TakeOptions,
  ): Promise<VoiceTakeResponse>;
  listRecordings(snippetId: string, signal?: AbortSignal): Promise<RecordingResponse[]>;
  fetchAudio(recordingId: string, signal?: AbortSignal): Promise<Blob>;
  deleteRecording(recordingId: string): Promise<void>;
  /** PUT the corrected transcript; returns the updated recording. */
  saveVerification(recordingId: string, text: string): Promise<RecordingResponse>;
  /** DELETE the correction (idempotent); returns the recording with both fields cleared. */
  clearVerification(recordingId: string): Promise<RecordingResponse>;
  datasetStats(signal?: AbortSignal): Promise<VoiceDatasetStats>;
  /** Authenticated zip download; the name comes from Content-Disposition, else `fallbackExportFilename(now())`. */
  exportDataset(options: ExportOptions, signal?: AbortSignal, now?: () => Date): Promise<DatasetDownload>;
}

export function createVoiceApi(http: AxiosInstance): VoiceApi {
  return {
    createTake: (canvasId, take, target, signal, options) =>
      wrap(() =>
        http.post<VoiceTakeResponse>(`/api/canvases/${encodeURIComponent(canvasId)}/voice-takes`, buildTakeForm(take, target, options?.vocabularyHints), {
          // The instance defaults to JSON, which would make axios serialise the FormData to JSON. In the
          // browser axios drops this header for FormData so the boundary is set by the browser.
          headers: { 'Content-Type': 'multipart/form-data' },
          signal,
        }),
      ),
    listRecordings: (snippetId, signal) =>
      wrap(() => http.get<RecordingResponse[]>(`/api/snippets/${encodeURIComponent(snippetId)}/recordings`, { signal })),
    fetchAudio: (recordingId, signal) =>
      wrap(() =>
        http.get<Blob>(`/api/recordings/${encodeURIComponent(recordingId)}/audio`, { responseType: 'blob', signal }),
      ),
    deleteRecording: async (recordingId) => {
      await wrap(() => http.delete(`/api/recordings/${encodeURIComponent(recordingId)}`));
    },
    saveVerification: (recordingId, text) =>
      wrap(() => http.put<RecordingResponse>(`/api/recordings/${encodeURIComponent(recordingId)}/verification`, { text })),
    clearVerification: (recordingId) =>
      wrap(() => http.delete<RecordingResponse>(`/api/recordings/${encodeURIComponent(recordingId)}/verification`)),
    datasetStats: (signal) => wrap(() => http.get<VoiceDatasetStats>('/api/recordings/stats', { signal })),
    exportDataset: async ({ includeUnverified }, signal, now = () => new Date()) => {
      try {
        const res = await http.get<Blob>('/api/recordings/export', {
          params: { include_unverified: includeUnverified },
          responseType: 'blob',
          signal,
        });
        const header: unknown = res.headers['content-disposition'];
        const filename = filenameFromContentDisposition(typeof header === 'string' ? header : null) ?? fallbackExportFilename(now());
        return { blob: res.data, filename };
      } catch (err) {
        await decodeBlobErrorBody(err);
        throw toVoiceError(err, EXPORT_STATUS_KINDS);
      }
    },
  };
}
